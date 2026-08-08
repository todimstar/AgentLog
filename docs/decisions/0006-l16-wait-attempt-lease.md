# ADR-0006 · Wait、Attempt 与 Lease（一棒怎么写、怎么不被抢、怎么不写两遍）

- **状态**：设计简报待审（2026-08-02 · L16 设计简报 · 铁律链条模式——交主人审思路 + 苏格拉底带练过关后才动后端）
- **关联**：直接续 [[0005-l15-acpp-session-ticket-handoff]]（本课把 L15 留的三个坑全填上：`active_attempt_id` 外键、`uk_contribution_ticket` 唯一键、P3 幂等欠账）；复用 [[0004-l14-single-agent-submit]] 的建草稿内核；消费 [[0003-l13-agent-assume-chain3]] 的 Chain 3 身份

---

## 背景 / 问题

L15 把**队列**建好了：两个 AI 对话能靠一根接力棒排出因果顺序。但队列里的人**还不能写字**——
没有草稿、没有帖子、前端零像素。L16 要把「一棒」的完整生命周期跑通。

一句话框定本课要解决的真实问题：

> **一张票排到了，怎么让它的持有者独占地写完、顺位交棒；
> 且独占权到期能自动失效（不靠任何后台任务），网络重发也不会写出两段正文？**

三个新概念（对新人的解释，讲义会展开）：

| 概念 | 是什么 | 为什么不能省 |
|---|---|---|
| **Attempt** | 一张票的**一次写作过程**。票 = 席位，attempt = 这次坐下来写 | 一张票可能被写好几次（超时后主人 retry，L18）。席位不变、attempt 重开。两者生命周期不同，必须分开记 |
| **Lease** | **有期限的独占权**（15 分钟），不是锁 | 锁需要有人来解；机娘的对话可能直接崩掉再也不回来。租约**到期自动失效**，无人解也不会永久卡死队列 |
| **Idempotency** | 同一请求发两次，效果与发一次**完全一致** | CLI 提交时网络超时，客户端无法判断服务端收没收到。重发不能变成两段正文 |

**本课交付**：3 个 Chain 3 端点 + 4 个 CLI 子命令 + 2 张表（V013）+ 幂等横切 + `ContentFacade`。
**不做**（`course_schedule` forbidden + 后续课边界）：超时 Worker（L17）、retry / terminate（L18）、
协作时间线前端（L17）、`content_origin` 计算（L20）、租约续租与心跳（列先建，L17/L18 用）。

→ **L16 结束时第一次能看见成品**：两个机娘接力写出的草稿，在浏览器审稿页里是两段正文、两个作者。
这是 L15「零像素」之后的兑现点。

---

## 取证结论（四层法反查 35 份设计文档，八个问题）

### 🔴 P1 `nextHandoffToken`：活契约与安全铁律**直接冲突**（主人已拍板）

| 出处 | 说了什么 |
|---|---|
| 活契约 `SubmitContributionResponse` | 有 `nextHandoffToken` 字段（下一棒令牌**明文**） |
| L12/L13/L15 一脉的令牌铁律 | 明文**绝不落库**，只在签发那一刻的 HTTP 响应里出现**一次**，库里只有 `HMAC-SHA256` 摘要 |

submit 发生时，尾令牌**早在 start/join 就签发过了**，库里只有 `BINARY(32)` 摘要——**拿不回明文**。
唯一能填上这个字段的办法是 submit 时吊销旧尾令牌、重签一根新的，但那会让**主人已经粘贴出去的旧令牌突然失效**
（下一个机娘拿着它来 join 会撞 409 REVOKED），体验是灾难。

**主人拍板：字段留空 + 登记漂移。** 依据：主人在 join 时已经拿到过尾令牌明文并存进了本地
`~/.agentlog/state/tickets/CT-xxxx.json`（L15 已实装），这个字段本来就是**冗余回显**。
**安全铁律不为一个冗余字段让步。**

### 🔴 P2 跨模块**写**：L15 登记的地雷，本课引爆（主人已拍板）

`02-architecture/架构总览.md` 画了 `collaboration --> content` 靠 `ContentFacade`，
但实装从未引入 Facade（D-05：跨模块**只读**走 SQL 投影，**写**只碰本模块表）。
而 submit 要写 content 的四张表。L14 的 `AgentIdentity` 只解了跨模块**读**，写无先例。

**主人拍板：实现 `ContentFacade`。** 这本来就是蓝图原设计——`content/package-info.java` 从 L02 起
就写着「对外入口规划为 `ContentFacade`」，**L16 正是它该出场的时候**。

### 🔴 P3 幂等：还掉 L15 的欠账（主人已拍板）

契约给 4 个端点声明了 `Idempotency-Key`（start / join / claim lease / submit），
L15 因幂等表排在 L16 而**声明了但不校验**（ADR-0005 的 P3，诚实登记为缺口）。

**主人拍板：横切拦截器，一次做全。** 一处实现覆盖 4 个端点，顺手还掉 P3。

### P4 `wait` 不是端点，是 CLI 命令

`course_schedule` 的 scope 写着 `wait`，容易误读成要做长轮询端点。核实：
`03-api/endpoint-catalog.md` 与活契约里 L16 **只有 3 个端点**，没有 wait。
`07-cli/cli-spec.md` 写的是 `agentlog collab wait --ticket CT-0002 --timeout-seconds 900`。

→ **等待发生在客户端**：CLI 循环调 `GET {ticketCode}`，按响应里的 `pollAfterSeconds` 决定睡多久。
服务端不持有任何长连接、不占线程。这是「**把等待留在最便宜的一侧**」。

### P5 蓝图用悲观锁，我们沿用 L15 的条件 UPDATE 范式（我裁决）

`transaction-boundaries.md` 的 TX-04 写「锁 Ticket」、TX-05 写「锁 Attempt 和 Ticket」——即 `SELECT ... FOR UPDATE`。
但 L15 已确立更好的范式：**一条带条件的 UPDATE，`affectedRows` 就是裁决书**。

两者对比（这一段进讲义，正面回答主人上次问的「SelectForUpdate 是什么」）：

| | `SELECT FOR UPDATE` + UPDATE | 条件 UPDATE（我们的） |
|---|---|---|
| 语句数 | **两条**，中间有应用往返 | **一条**，无缝隙 |
| 锁持有时长 | 从 SELECT 到事务结束 | 语句内部，极短 |
| 判定权 | 在 Java 里 | 在 SQL 的 `WHERE` 里 |
| 失败表达 | 靠 Java 抛异常 | `affectedRows` = 0/1 |

`FOR UPDATE` 不是错的（L17 的 Worker 扫描就必须用 `FOR UPDATE SKIP LOCKED`，因为要**批量占有**多行再逐条处理），
但对「判定一行能不能改」这种场景，条件 UPDATE 更短、更原子、更少往返。**登记为漂移，不是照抄。**

### P6 Attempt 状态机的 `READY` 态**永不出现**（我裁决）

`10-reliability/ACPP状态机.md` 写 `READY -> ACTIVE -> SUCCEEDED`，
但 TX-04 第 4-5 步是「创建新 attempt + 写入 lease」——**创建时就带着租约**，直接是 `ACTIVE`。
`READY` 没有任何路径能产生。

**裁决**：代码不产生 `READY`，但 **CHECK 约束的值集保留它**（同 V012 的做法：值集取状态机全集，
免得后续课改状态机还要再来一条迁移）。在建表注释里写明「此值当前无产生路径，为 L18 retry 预留」。

### P7 迁移编号 V010 → **V013**

蓝图写 `V010__create_attempt_and_idempotency.sql`，但主线 V010 早被设备配对占用（D-02/D-09/D-15 同款）。
仓库已到 V012，**下一个可用 = V013**。

### P8 ⚠️ 时区雷：新测试类必须对齐容器时区

L15 实测：主应用 JDBC URL 带 `serverTimezone=UTC`，而 Testcontainers 自建 URL **不带**，
`NOW(3)` 与 Java 写入的 `DATETIME` 差 **8 小时**。L16 新增的每个测试类
**必须** `withUrlParam("serverTimezone", "UTC")`。（其余 6 个既有测试类仍未对齐，L17 前统一——本课不扩范围。）

---

## 决策

### 决策 1 · V013 建两表 + 填 L15 留的两个坑

```sql
contribution_attempt   -- 一张票的一次写作过程；租约内嵌在这张表里（不单开 lease 表）
  uk_attempt_ticket_no      (ticket_id, attempt_no)      -- 同一张票的第 N 次尝试只能有一条
  uk_attempt_lease_digest   (lease_token_digest)         -- 租约令牌摘要唯一：既是查找键也防撞
  idx_attempt_expiry        (status, lease_expires_at)   -- 供 L17 Worker 扫超时
idempotency_record     -- 幂等台账
  uk_idempotency  (owner_user_id, agent_id, endpoint, idempotency_key)
```

**为什么 Lease 内嵌在 Attempt 里、不单开一张表**：租约与尝试是**同生共死**的——
一个 attempt 有且仅有一个租约，租约失效等于这次尝试失败。这跟 L15 的 ticket/handoff **正好相反**
（那两者基数会破 1:1、状态机正交，所以必须分表）。**同一课里给出「该合」与「该分」的两个反例，是本课的设计教学点。**

补 L15 的坑：
```sql
ALTER TABLE contribution_ticket ADD CONSTRAINT fk_ticket_active_attempt ...   -- V012 建列不建 FK，现在补
ALTER TABLE contribution ADD UNIQUE KEY uk_contribution_ticket (ticket_id);   -- 一张票只能产出一条贡献
```
`uk_contribution_ticket` 是 **submit 的不变量**，也是幂等之外的**最后一道安全网**
（正常路径永不触发——同 L15 对 `uk_ticket_sequence` 的定位）。

### 决策 2 · `ContentFacade`：跨模块写的第一条正路

```java
// com.agentlog.content —— 模块根包 = Spring Modulith 认定的公开 API
public interface ContentFacade {
    AppendResult appendAgentContribution(AppendCommand cmd);
}
```
- 实现类 `ContentFacadeImpl` 放 `content.application`（**私有子包**），collaboration 只看得见接口 → `ModularityTest` 绿；
- **一个方法覆盖首棒与后续棒**：`cmd.draftId() == null` 判定首棒 → 复用 L14 的 `createDraftInternal` 建 post+draft+contribution+block；
  非空 → 只 append contribution + block（`display_order = 现有最大 + 1`）；
- 顺手回填 `contribution.session_id / ticket_id`（V005 预留、V012 建了外键、**至今无人写值**的两列，本课第一次填上）；
- **draftUrl 不由 Facade 产出**——Facade 只管数据，URL 是表现层的事，由 collaboration 的 Controller 拼
  （与 L14 `AgentDraftController` 同一套 `webBaseUrl + "/#/owner/drafts/" + draftId`）。

### 决策 3 · Claim Lease：闸门必须在建 Attempt **之前**（L15 的教训直接复用）

```
① 查 ticket + session（只取上下文与做 404 行级授权，不做状态判定）
② ★闸门★  UPDATE contribution_ticket
              SET status='LEASED', updated_at=?
            WHERE ticket_code=? AND status='READY_TO_WRITE' AND required_agent_id=?
          affectedRows=0 → 回查翻译错误码（409 / 403 / 404）
③ 建 attempt（到这里已独占，零竞争）：attempt_no = MAX+1，status=ACTIVE，写租约摘要与 15min 到期
④ 回填 ticket.active_attempt_id（按主键更新，无竞争）
⑤ 首棒时 session: OPEN → RUNNING
```

**顺序为什么是这个**：若写成「查 → 建 attempt → 改票」，两个线程会**双双先建 attempt**，
撞 `uk_attempt_ticket_no` → 败者拿 `DuplicateKeyException`（500），而不是干净的 409。
**这正是 L15 我踩过的那个坑的同构版本**，闸门优先之后唯一键退回「最后安全网」的本分。

`required_agent_id` 也写进 `WHERE`（而不是先 SELECT 再判）——虽然这一列建票后不会变、理论上没有 TOCTOU，
但保持「**判定权 100% 在那条 UPDATE 手里**」的范式纯度；失败后回查翻译成 `ACPP_WRONG_AGENT`(403)。

### 决策 4 · Submit：闸门是 Attempt，写 content 在闸门之后（同一事务）

```
① 幂等（横切已处理，业务代码看不见）
② ★闸门★  UPDATE contribution_attempt
              SET status='SUCCEEDED', finished_at=?
            WHERE lease_token_digest=? AND status='ACTIVE' AND lease_expires_at >= #{now}
          affectedRows=0 → 回查翻译：410 LEASE_EXPIRED / 409 ALREADY_CLAIMED / 403 LEASE_INVALID
③ 取上下文（attempt → ticket → session），校验 ticket 归属与 agent 一致
④ contentFacade.appendAgentContribution(...)  ← 跨模块写，同一事务
⑤ ticket → DONE；session.last_completed_sequence 推进
⑥ 唤醒直接后继：WAITING_PREDECESSOR → READY_TO_WRITE（predecessor_ticket_id = 本票）
⑦ session → AWAITING_CONTINUATION
⑧ 返回 draftUrl（nextHandoffToken 留空，见 P1）
```

**过期判定写在 `WHERE` 里做惰性执行**（`lease_expires_at >= #{now}`），与 L15 的 handoff 完全一致：
**正确性不依赖 L17 的清理 Worker 是否及时**——即使超时的租约还挂着 `ACTIVE`，也一定提交不进来。
这正是本课能在 forbidden「不写超时 Worker」的前提下依然安全的原因。

⚠️ **时刻用应用时钟 `#{now}` 而不是 SQL 的 `NOW(3)`**（L15 血的教训，P8）。

### 决策 5 · 幂等：AOP 环绕切面（不是 Filter、不是 Interceptor）

```java
@Idempotent            // 标在 Controller 方法上
```
```
切面 @Around：
  ① 算 requestHash = SHA-256(路径参数 + 请求体)          ← 路径参数必须进 hash，否则同 key 换个 ticket 会被误判为重放
  ② INSERT idempotency_record(IN_PROGRESS)  【REQUIRES_NEW 独立事务，立即提交】
       └ 撞 uk_idempotency → 查旧记录：
            COMPLETED   + 同 hash → 直接反序列化旧响应返回（业务【完全不执行】）
            IN_PROGRESS + 同 hash → 409 IDEMPOTENCY_REQUEST_IN_PROGRESS
            不同 hash            → 409 IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY
  ③ 执行业务（它自己的 @Transactional）
  ④ 成功 → UPDATE 记录为 COMPLETED + 存 response_body_json 【独立事务】
     失败 → DELETE 记录 【独立事务】，让客户端能用同 key 重试
```

**为什么用 AOP 而不是 L13 那两层 Filter**：Filter/Interceptor 拿到的是字节流，
要缓存响应体必须包 `ContentCachingResponseWrapper`；而切面直接拿到**返回值对象**，
Jackson 序列化存库、重放时反序列化返回，干净得多。这也是本项目 **AOP 的第一次出场**（教学点）。

**为什么幂等记录必须用 `REQUIRES_NEW` 独立事务**：若与业务共用事务，业务回滚会把幂等记录一起带走，
「处理中」的状态对并发的第二个请求就**不可见**——幂等直接失效。

**`endpoint` 列存路由模板**（`POST /api/v1/agent/contribution-tickets/{ticketCode}/leases`）而不是实际 URI：
实际 URI 含 ticketCode，会把同一个 key 在不同票上的使用切成两个幂等域，反而**破坏**幂等语义。

**诚实登记的窗口**：业务成功后、④ 之前进程崩溃 → 记录停在 IN_PROGRESS，客户端重试拿 409。
这是 at-most-once 的固有窗口，24h 后随记录过期自愈。**写进 CHANGELOG 与讲义，不藏。**

### 决策 6 · Lease 令牌：仿 handoff，明文只出现一次

`tokenService.generateRawToken("lease_")` → 明文只在 `ClaimLeaseResponse.leaseToken` 返回一次，
库里存 `HMAC-SHA256` 摘要。CLI 存进本地 `~/.agentlog/state/tickets/CT-xxxx.json`（L15 已有该文件，扩字段）。
提交时走 `X-Turn-Lease-Token` 头（活契约已定义此 securityScheme）。

**与 handoff 的关键区别**：handoff 要**经过主人的手**复制粘贴（跨 AI 对话传递），所以必须回显给人；
lease **不经过人**，签发给谁就是谁用，CLI 自己存自己用——所以 `cli-spec.md` 的输出规则里
明写「**不输出 lease token**」。同样是令牌，可见性策略完全不同，因为**传递路径不同**。

### 决策 7 · CLI 四命令（`files` 栏只写后端两文件，但验收要「后序等待」→ 范围必须含 CLI）

```bash
agentlog collab status     --ticket CT-xxxx                      # 查一次
agentlog collab wait       --ticket CT-xxxx --timeout-seconds 900 # 循环 status 直到 READY_TO_WRITE
agentlog collab claim-turn --ticket CT-xxxx                      # 领租约，令牌存本地
agentlog collab submit     --ticket CT-xxxx --file contribution.md
```
`wait` 的退避：读服务端给的 `pollAfterSeconds`（服务端掌握节奏，客户端不自作主张）。
超时退出码非 0，stderr 给人话提示。**stdout=JSON / stderr=人**（L13 起的约定）。

---

## 后果

**正面**：第一次产出**多机娘协作的可见成品**（两段正文、两个作者的草稿）；
`ContentFacade` 为后续所有跨模块写立了范式；幂等横切一次覆盖 4 个端点并还清 P3 欠账。

**负面 / 成本**：
- 引入 AOP 增加一层「看不见的魔法」——用 `@Idempotent` 显式注解 + 讲义专章抵消；
- `ContentFacade` 让 collaboration 与 content 产生编译期依赖（此前零依赖）——但方向是单向的、
  且由 `ModularityTest` 站岗，不会退化成互相引用；
- 幂等台账是写放大（每个幂等请求多两次 DB 往返），代价换正确性，且 24h 后清理（L17 Worker）。

---

## 冻结面登记（DRIFT **D-16**，本课收尾时写入 Pack）

| # | 漂移 | 依据 |
|---|---|---|
| 1 | 迁移编号 V010 → **V013** | 主线编号已被占用（续 D-02/D-09/D-15） |
| 2 | `SubmitContributionResponse.nextHandoffToken` **不产出** | 明文不落库铁律 > 契约冗余字段（主人拍板） |
| 3 | TX-04/TX-05 的悲观锁 → **条件 UPDATE** | 续 L15 范式，更原子、更少往返（P5） |
| 4 | Attempt 的 `READY` 态无产生路径 | 状态机文档与 TX-04 自相矛盾（P6） |
| 5 | **引入 `ContentFacade`** —— 首次实现蓝图的跨模块写通路 | 回归蓝图原设计，D-05「写只碰本模块表」在此**受控放宽**（主人拍板） |
| 6 | `ACPP_TICKET_*` / `ACPP_LEASE_*` / `ACPP_WRONG_AGENT` / `IDEMPOTENCY_*` 错误码实装 | error-codes.md 已规划，本课落地 |

---

## 测试计划（tests-first）

| # | 测试 | 守什么 |
|---|---|---|
| 1 | **两线程 claim 同一 ticket lease，恰好一个成功**（皇冠·并发清单 #2） | 输家必须拿干净 409，**不是** DuplicateKeyException(500) |
| 2 | 加压 8 线程同上 | 2 线程可能侥幸错开（L15 教训） |
| 3 | 同 Idempotency-Key + 同 body → 同结果，且业务**只执行一次**（清单 #5） | 断言库里只有一条 contribution |
| 4 | 同 key + 不同 body → 409（清单 #6） | |
| 5 | 租约过期后 submit → 410 | 惰性判定，**不启动任何 Worker** |
| 6 | 非 required agent 领租约 → 403 | 多租户 + 席位归属 |
| 7 | 跨 owner 访问 ticket → **404 而非 403** | 防资源枚举（对齐 L15） |
| 8 | 首棒 submit 建 post+draft；二棒 submit 只 append block | Facade 的分支 |
| 9 | 后序票在前序 DONE 后自动 → READY_TO_WRITE | 验收项「后序等待」 |
| 10 | `storedExpiryAgreesWithDatabaseClock` 回归 | 时区雷站岗（P8） |
| 11 | `ModularityTest` 保持绿 | Facade 没有破坏模块边界 |

⚠️ 所有新测试类的 Testcontainers **必须** `withUrlParam("serverTimezone", "UTC")`。

---

## 遗留待决（登记，本课不做）

1. **超时 Worker（L17）**：租约过期后 attempt 仍挂 `ACTIVE`、ticket 仍挂 `LEASED`，
   状态**不会自愈**——但**正确性不受影响**（惰性判定挡住了提交）。L17 的 Worker 负责把状态刷成
   `FAILED_TIMEOUT` 并冻结后序。本课要在讲义里说清「状态不准 ≠ 行为不对」。
2. **续租 / 心跳**：`last_heartbeat_at`、`max_lease_expires_at` 两列本课**建列不用**（同 V012 的 `active_attempt_id`）。
3. **幂等记录清理**：24h 过期靠 L17 的清理 Worker，本课只写 `expires_at` 与索引。
4. **其余 6 个测试类的容器时区未对齐**：L17 前统一（progress-pointer 已记）。
