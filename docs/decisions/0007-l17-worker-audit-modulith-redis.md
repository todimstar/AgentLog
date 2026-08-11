# ADR-0007 · Worker、Audit、Modulith 与 Redis（一棒死了之后，秩序怎么自己站起来）

- **状态**：设计简报（2026-08-10 · L17 · 铁律链条模式 **v2** 首次实践——黑板课 → 辩论 → 施工蓝图 → 带练 → 施工）
- **关联**：直接续 [[0006-l16-wait-attempt-lease]]（还它的两笔账：`fk_attempt_error` 外键、TX-05 第 11 步的领域事件）；首次落地 Pack `10-reliability/modulith-events.md` 与 `redis-token-bucket.lua`

---

## 背景 / 问题

L16 反复说过一句话：**「状态不准 ≠ 行为不对」**。当时是为了说明"不写 Worker 也安全"。
L17 要补上那句话的后半截——**状态不准，是会骗人的**。

```
机娘A 领了第 2 棒的租约（15 分钟）→ 对话崩了 → 15 分钟过去
数据库里：attempt.status = 'ACTIVE'      ← 没人改它
         ticket.status  = 'LEASED'      ← 没人改它
         handoff.status = 'AVAILABLE'   ← 尾令牌还挂着
```

| 谁在看 | 他看到什么 | 真相 |
|---|---|---|
| 主人（时间线页，L18） | 「第 2 棒正在写」 | 它已经死了 15 分钟 |
| 机娘B（排第 3 棒） | 「前一棒还没完成，我继续等」 | **它永远等不到了** |
| 机娘A（换新对话回来） | 「我这一棒怎么了？」 | 没有任何地方告诉它 |

一句话框定本课：

> **一棒死了之后，谁去宣布它死了、告诉后面的人别等了、并留下一份可查的记录？**

**本课交付**：V014 三表 + `reliability`/`audit` 两个新模块 + Modulith 事件 + Redis 令牌桶限流。
**不做**（`course_schedule` forbidden + 后续课边界）：retry / terminate（L18，**属于人的决策链**）、协作时间线页（L18）。

---

## 取证结论

### P1 迁移编号 V011 → **V014**

Pack 规划 `V011__create_audit_and_error.sql`，但主线 V011 早被 `agent_acting_session` 占用。
续 D-02/D-09/D-15/D-16 的编号顺延约定。

### P2 `event_publication` 表**自己建，不让 Modulith 自动建**

Modulith 支持 `schema-initialization.enabled=true` 自动建表，但那会**绕过 Flyway** →
破「数据库结构的唯一真相源是 Flyway」铁律（不同环境可能长得不一样）。
Pack `10-reliability/modulith-events.md` 也明确要求配 `enabled=false`。
代价：列定义要对齐 `spring-modulith-events-jdbc-1.4.11` 的 `schema-mysql.sql`，**Modulith 升级需跟进**。

### P3 蓝图的 TX-06 是「大事务批处理」，实装改成「每条独立小事务」

Pack `04-database/transaction-boundaries.md` 的 TX-06 只写了扫描 SQL + 逐条要做的事，
没说事务边界。若把 50 条放一个大事务：**第 23 条炸 → 整批回滚 → 30 秒后又扫到同样 50 条 →
又在第 23 条炸 → 永久卡死**，那 49 条正常的永远处理不了。

这个故障模式叫**毒丸消息（poison pill）**。解法：每条一个 `@Transactional`，Worker 侧 `try/catch` 兜住，坏的记日志跳过。

### P4 `AuditRecord` 记的不是"帖子历史"（澄清一处易混）

| 表 | 记什么 | 哪课 |
|---|---|---|
| `audit_record` | **动作流水**：「机娘#7 在 14:03 领了第 2 棒的租约」 | L17 |
| `post_version` | **已发布内容的不可变快照** | L06 建表，L20 用 |
| `draft_revision` | **草稿编辑历史** | L19 |

`audit_record` 里**没有正文**，只有动作。

### P5 限流本课只挂 ACPP 两个端点（主人拍板）

Pack `10-reliability/MySQL与Redis边界.md` 列了五个场景（登录 / 配对 / claim handoff / 查票状态 / 上传槽位），
但本课 `files` 栏只有 `reliability | audit | redis-token-bucket.lua`。
**只挂 claim handoff + 查票状态**——动登录路径要重跑 L05/L11.5 的测试，范围会扩。

---

## 决策

### 决策 1 · V014 三表 + 一个外键

```sql
error_report      -- 一次事故的现场：卡在哪 / 为什么 / 建议怎么办（suggested_actions_json）
audit_record      -- 动作流水：谁、何时、做了什么
EVENT_PUBLICATION -- Modulith 事务性发件箱
ALTER TABLE contribution_attempt ADD CONSTRAINT fk_attempt_error ...   -- 还 V013 的账
```

**两张流水表与状态表的三处不同**（本课的建模教学点）：

| | 状态表（ticket/attempt/session） | 流水表（error_report/audit_record） |
|---|---|---|
| `updated_at`/`version` | ✅ 有 | ❌ **没有**——只 INSERT 永不 UPDATE，历史不能改 |
| 唯一键 | ✅ 有 | ❌ **没有**——同一件事可能发生两次（retry 后再失败） |
| 形状 | 列固定 | **大量可空列 + JSON 兜底** |

**JSON 列的规矩**：需要**查**的字段必须提成正式列（`owner_user_id`/`session_id`/`ticket_id`/`agent_id`），
只是"看一眼"的细节才进 JSON。否则表会变成五十列、四十列永远是 NULL。

**为什么 error_report 和 audit_record 分表**（本课第三次"合与分"判断）：
基数不同（一次失败 1 条 error，一次协作 N 条 audit）、写入时机不同（只在失败 vs 每个动作）、
**且 error_report 被 `contribution_attempt.error_report_id` 外键引用，audit_record 没有**。→ 分表。

### 决策 2 · `FOR UPDATE SKIP LOCKED`：让数据库当任务队列

```sql
SELECT id FROM contribution_attempt
WHERE status = 'ACTIVE' AND lease_expires_at < #{now}   -- ★ 应用时钟，不是 NOW(3)
ORDER BY lease_expires_at LIMIT #{batchSize}
FOR UPDATE SKIP LOCKED
```

**为什么 Worker 用 `FOR UPDATE` 而不是 L15/L16 的条件 UPDATE**：处理一条过期 attempt 要做**七八件事**
（改 attempt、改 ticket、改后序、冻结令牌、写 error_report、发事件），不是一条 UPDATE 能完成的。
所以必须先"把这批占住"，再慢慢做。

| | 条件 UPDATE（L15/L16） | `FOR UPDATE`（L17） |
|---|---|---|
| 目的 | 判定**一行**能不能改 | **批量占有多行**，逐条慢慢处理 |
| 适合 | 抢占（谁赢谁改） | 批处理（我要拿 50 条活儿） |

**`SKIP LOCKED` = 「已经被别人锁住的行，我不等，直接跳过去拿下一批」**。
没有它，实例2 会**排队等**实例1 处理完那 50 条——两个 Worker 变成串行，白养一个。

> **不用 Redis 分布式锁**：Pack `MySQL与Redis边界.md` 明确「**不要用 Redis 锁替代数据库状态机**」。
> `SKIP LOCKED` 不需要外部依赖、不需要选主，谁先锁到谁干。

⚠️ **用应用时钟 `#{now}` 而非 `NOW(3)`**：蓝图 TX-06 原文写的是 `NOW(3)`，那正是 L15 埋的时区雷
（Java 写入 vs 数据库读取，口径由 JDBC 参数决定，实测差 8 小时）。而且**这里比 L16 更危险**：

| | L16 submit 的 `>= #{now}` | L17 Worker 的 `< #{now}` |
|---|---|---|
| 判错的后果 | 该拒的放过 / 该放的拒了 | 少捞几条 / 多捞几条 |
| 测试能不能抓到 | ✅ **立刻红** | ❌ **静默**（只验"能捞到"，捞的时机偏 8 小时也过） |

→ 专门写一条探针：造**刚过期 1 秒**的 attempt，断言能捞到。时区一错立刻红。

### 决策 3 · `expireOne` 的闸门：防的是另一个 Worker，不是机娘

```java
@Transactional   // ★ 每条一个小事务（毒丸防线）
public void expireOne(long attemptId) {
    int affected = scanMapper.markAttemptTimeout(attemptId, now);   // ★闸门★
    if (affected == 0) return;    // 别人已经处理了
    // ... 后面七步没有竞争者
}
```

**主人在施工蓝图评审时质疑过这道闸门**：「ids 都是从 attempt 里找出来的超时请求，
就算之后机娘 submit 了也不应该对草稿做出任何改变吧？」

**他说得对**，两个条件互斥：

```
submit  的 WHERE：status='ACTIVE' AND lease_expires_at >= #{now}   ← 未过期才能提交
Worker 的 WHERE：status='ACTIVE' AND lease_expires_at <  #{now}   ← 已过期才会被捞
```

**闸门防的是另一个 Worker**：第一步 `FOR UPDATE` 的锁在**那个事务结束时就放了**，
`expireOne` 开始时那条 attempt 可能已被另一个实例（或重启后的自己）处理完。

**且必须放在七步之前**——若放在第四步才检查，前三步已经改了状态，闸门返回 0 时那些改动无法收回。

### 决策 4 · 模块边界：`CollaborationFacade`（★ 被 `ModularityTest` 打红之后才补的）

**这是本课最有价值的一次"被机器纠正"，诚实记录。**

施工蓝图里我把 `ExpireAttemptService`（含那七步状态推进）放在 `reliability` 模块，
结果 `ModularityTest` 一次跑出 **12 条违规**：

```
Module 'reliability' depends on non-exposed type ...ContributionAttemptMapper within module 'collaboration'!
Module 'reliability' depends on non-exposed type ...ContributionTicketDO within module 'collaboration'!
...（共 12 条）
```

Worker 要改 attempt/ticket/session 的状态，就得 import collaboration 的 Mapper 与 DO——
而那些都在**私有子包**里。

**修法**（与 L16 的 `ContentFacade` 完全同构）：在 collaboration 的**模块根包**开
`CollaborationFacade`，把"宣告一棒超时"作为它的公开能力；实现类 `ExpireAttemptFacadeImpl`
留在 `application` 私有子包；Mapper 与 XML 一并搬回 collaboration。

**★ 修完之后才想清楚的划分判据**：

> **「何时做」归 reliability，「做什么」归 collaboration。**

那七步全是 **ACPP 协议自己的状态机推进**，是 collaboration 的领域知识；
reliability 的职责只是**触发时机**（定时扫描、批量占有、毒丸隔离）——
它不该知道"一棒死了以后状态该怎么流转"。

这个分工顺带给 L18 的 retry 留好了落点：那同样是状态机推进，同样该由本 Facade 暴露。

**教训**：`ModularityTest`（L02 建的）的价值在这里兑现了——**边界不靠自觉，靠机器强制**。
人在赶工时一定会顺手 import；测试不红，这个设计缺陷会一直留在代码里。

### 决策 5 · Modulith 事件：Worker 只喊一声

Worker 处理一条过期 attempt，要做的事里有两件**不属于 collaboration 模块**：写 ErrorReport（reliability）、
写 AuditRecord（audit）。将来还要通知主人（notification，L22）。

直接调它们 → **Worker 越来越胖，加第四个订阅者就得改它一次**。

```java
events.publishEvent(new AttemptExpired(...));   // Worker 只喊一声

@ApplicationModuleListener                       // audit 模块自己听
void on(AttemptExpired e) { auditService.record(...); }
```

**Modulith 比普通 Spring 事件多给了什么**：普通事件的监听器执行失败，**事件就丢了**。
Modulith 在**业务的同一个事务里**把事件写进 `EVENT_PUBLICATION` 表（**事务性发件箱 / Transactional Outbox**），
监听器成功则完成、失败则留存、重启后重投 → **业务成功了，派生行为一定不会丢**。

**边界**（Pack `modulith-events.md` 划的线）：事件**不负责** Handoff / Attempt Lease / 幂等 / Worker /
失败传播 / 产品审计语义。
→ **不变量永远由 MySQL 状态机守，事件只做派生行为。** 事件是异步的，不能用来守不变量。

**为什么 L16 的 submit 没用事件、L17 的 Worker 用了**：submit 要**同事务返回 draftUrl**，异步对不上；
Worker 不需要返回值，用事件正好。（L16 欠的那笔账本课一并还上——submit 现在也发 `ContributionSubmitted`，
但只用于**派生**的审计，状态推进仍在原事务内同步完成。）

### 决策 6 · 失败传播：票批量阻塞（N 张），令牌单根冻结（1 根）

```
第 2 棒超时死了：
  ① attempt#2        → FAILED_TIMEOUT           （1 条）
  ② ticket#2         → FAILED_TIMEOUT           （1 张）
  ③ ticket#3、#4…    → BLOCKED_BY_PREDECESSOR   （N 张）
  ④ 尾令牌           → FROZEN                    （永远 1 根）
  ⑤ session          → INVALIDATED（首棒）/ PAUSED_ON_ERROR（中间）
```

**不是"一路冻结"**——票是链表节点，有几棒就有几张；而尾令牌永远只有一根悬空的，
前面那些已 `CONSUMED` 的是终态，不用动。

这正是 L15 那**两个正交维度**在同一次事故里同时动了：
- `ticket → BLOCKED` 管「**已经进来的人**能不能写」
- `handoff → FROZEN` 管「**还能不能有新人**进来」

**为什么后者也要冻**：链条已断在第 2 棒，再让新机娘拿尾令牌排第 6 棒，它也永远等不到前序完成。
**早拒绝比让它排进来再干等好。**

**为什么首棒失败是 `INVALIDATED` 而非 `PAUSED_ON_ERROR`**：首棒失败意味着 post/draft **从来没被创建过**
（L15 `APPROVAL_RECORD` 的「首棒失败不暴露空草稿」），没有任何东西可救。

### 决策 7 · 为什么 Worker 不自动 retry

**机娘的对话已经崩了，服务端叫不醒它。** 自动 retry 只会把票改回 `READY_TO_WRITE`，
然后没人来写，15 分钟后再超时一次 → **死循环**。

retry 必须由人触发，因为**只有人能开一个新对话、让机娘重新 `collab claim-turn`**。
所以 L17 禁止项写「不写 retry」不是偷懒，**是它属于另一条链——人的决策链**（L18）。

### 决策 8 · Redis 令牌桶：限流不是不变量

| | MySQL | Redis |
|---|---|---|
| 每次请求 | 一次事务、一次磁盘写 | 一次内存操作 |
| 限流是不是"真相" | ❌ **不是**。多放行几次没有任何后果 | 够用 |

**关键判断：限流不是不变量。** 少限/多限几次不会导致数据错乱，所以可以放在"快但不那么可靠"的地方。
而 L15/L16 那些闸门（谁抢到令牌、谁抢到租约）是**真不变量**，必须留在 MySQL。

**令牌桶 vs 固定窗口计数器**：计数器有"整分钟边界"问题——`00:59.9` 打 120 次、`01:00.1` 再打 120 次，
0.2 秒内实际 240 次，但两个窗口各自都"没超"。令牌桶是**连续**的：桶容量 = 瞬时能爆发多少，
补充速率 = 长期平均多少，两个参数分别管两件事。

**为什么用 Lua 脚本**：算令牌要四步（读当前值 → 算补充 → 判断够不够 → 写回），
分四条命令发过去中间会被插入 → **又是 TOCTOU**。Redis 执行 Lua 是原子的。

> ★ **这是同一个思想的第三种形态**：
> 改已有的行 → 条件 UPDATE（L15/L16）· 建全新的行 → 抢占 INSERT（L16 幂等）· Redis 侧 → **Lua 脚本**（L17）。
> 三种实现、一个道理：**让"检查"和"动作"在一个不可分割的单位里完成。**

**故障策略**（Pack `MySQL与Redis边界.md`）：登录/配对 **fail closed**（安全优先）；
普通读 **fail open**（限流只是容量保护）；ACPP 写继续依赖 MySQL + 记告警。
本课实装的两个端点走 **fail open**。

---

## 后果

**正面**：状态终于会自愈，时间线页（L18）有了可信的数据源；`reliability`/`audit` 两个模块立起来；
事务性发件箱为后续所有跨模块派生行为立了范式。

**负面 / 成本**：
- 多了一个"始终在跑"的东西（Worker），要考虑多实例、批大小、扫描间隔——全部做成可配；
- `EVENT_PUBLICATION` 表的列定义**绑定 Modulith 版本**，升级需跟进（这是不让它自动建表的代价）；
- 审计流水会持续增长——但**先算过量级**：3 棒协作 ≈ 10 条，每天 100 篇 → 一年 36.5 万条 ≈ 70MB，
  **十年不用动它**。真到瓶颈按 **索引 → 归档 → 分区 → 分表** 的阶梯走，不跳步。

---

## 冻结面登记（DRIFT **D-17**，本课收尾时写入 Pack）

| # | 漂移 | 依据 |
|---|---|---|
| 1 | 迁移编号 V011 → **V014** | 主线编号已被占用（续 D-02/D-09/D-15/D-16） |
| 2 | TX-06 的批处理 → **每条独立小事务** | 毒丸消息防线（P3） |
| 3 | 扫描 SQL 的 `NOW(3)` → **应用时钟 `#{now}`** | 续 D-15 的时区教训，且此处判错是**静默**的 |
| 4 | `EVENT_PUBLICATION` 手工建表走 Flyway | 「数据库结构唯一真相源是 Flyway」铁律 |
| 5 | 限流本课只挂 ACPP 两个端点 | 范围控制（主人拍板，P5） |
| 6 | 错误码 +1：`RATE_LIMIT_EXCEEDED`(429) | Pack 错误码表未列，限流必需 |
| 7 | `@EnableScheduling` 补在 `AgentLogApplication` | 此前只有 `@EnableAsync`，定时任务不会触发 |
| 8 | **12 个既有测试类补齐容器时区** | L15 就欠着（progress-pointer 记的「其余 6 个」实为 12 个），L17 起 Worker 按时间扫表，不统一会本地过 CI 挂 |

---

## 测试计划（tests-first）

| # | 测试 | 守什么 |
|---|---|---|
| 1 | 刚过期 **1 秒**的 attempt 能被捞到 | 🔴 时区探针（决策 2 的 ⚠️） |
| 2 | 中间棒超时 → 后序 `BLOCKED` + session `PAUSED_ON_ERROR` | **硬验收①「超时阻塞后序」** |
| 3 | 首棒超时 → session `INVALIDATED`，且 draft 从未创建 | 「首棒失败不暴露空草稿」 |
| 4 | 两个 Worker 并发扫，error_report **只有一条** | **硬验收②「多 Worker 不重复」** |
| 5 | Worker 先行后 submit → **410 LEASE_EXPIRED** | 并发清单 #3（submit 与 Worker 竞争） |
| 6 | Worker 扫完后审计记录写入 | Modulith 事件链路通 |

⚠️ 本测试类**不加 `@Transactional`**：Worker 和测试业务必须各自独立事务，否则测不出并发语义（L15 教训）。

---

## 遗留待决（登记，本课不做）

1. **retry / terminate 端点**（L18）——属于人的决策链，见决策 6。
2. **协作时间线页**（L18）——本课的 `audit_record` 就是它的数据源。
3. **「重新签发尾令牌」端点**（L17 原计划，顺延 L18）——挂在时间线页上更自然。
4. **其余三个 Worker**（媒体清理 / 草稿快照清理 / 幂等台账清理）——参数已在 `application.yml` 占位，实现待后续课。
