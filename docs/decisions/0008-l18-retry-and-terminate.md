# ADR-0008 · Retry 与 Terminate（冻住之后，谁来解冻）

- 状态：已实施
- 日期：2026-08-16
- 会话：`ed69ff80`（继承 L16/L17 会话 `3ad9ede8` 的上下文与讲解风格）
- 课次：L18
- 相关：ADR-0007（L17 Worker/Audit/Modulith）、`APPROVAL_RECORD.md`、DRIFT **D-18**

## 背景 / 问题

L17 让秩序在崩溃后**自愈**了。但自愈的结果是**冻住**：

| 东西 | L17 之后 |
|---|---|
| attempt | `FAILED_TIMEOUT` |
| 超时那张票 | `FAILED_TIMEOUT` |
| 后序整条尾巴 | `BLOCKED_BY_PREDECESSOR` |
| 尾令牌 | `FROZEN` |
| 会话 | `PAUSED_ON_ERROR` |

**L17 写的所有代码里，没有一行能让这个局面重新动起来。** 本课就是那条路，而它的第一个特点是——**必须由人来走**。

### 为什么不能让 Worker 自动 retry

```text
Worker 自动把票改回 READY_TO_WRITE → ……然后等谁来写？
原机娘的对话已经崩了。服务端【没有任何办法】叫醒一个已关闭的 AI 对话窗口。
15 分钟后再超时 → 又自动重启 → 又没人来 → 死循环，每轮往 error_report 写一条。
```

根因：**服务端与机娘之间是「拉」不是「推」的关系**。机娘来问「轮到我了吗」，服务端只能回答；它**永远无法主动发起**一次机娘的写作，连对方还在不在都不知道。

⇒ **判据（human-in-the-loop 的判定标准，不是「人比机器聪明」）：这一步需要的信息只存在于系统之外。**

## 取证结论

### P1 迁移编号 → **V015**（+ 施工期追加 V016）

蓝图没为本课规划迁移（它以为 retry 只改状态不改结构）。仓库到 V014，下一个可用 = V015。
V016 是施工期被死锁打红后追加的修复，**单独成条以留下"这是踩出来的"这个痕迹**。

### P2 🔴 蓝图的 `READY` 态方案不完整——它没有配套的回收 Worker

蓝图原始契约 `RetryTicketResponse` 把 `attemptNo` 列为**必填**，加上状态机的 `READY -> ACTIVE`、TX-04 的「创建 Attempt」，拼出的完整设想是：

```text
retry → 建 attempt(attempt_no=2, status=READY) → 返回 attemptNo
机娘 claim-turn → READY → ACTIVE（此刻才写入租约）
```

**但 `10-reliability/worker-parameters.yaml` 列了全部 5 个 Worker，没有一个扫「READY 但没人领」的 attempt**——L17 那个扫的是 `status='ACTIVE' AND lease_expires_at < now`，`READY` 行没有 `lease_expires_at`，**永远捞不到**。

⇒ 蓝图会长出「名额发了、原机娘再也不回来」的**僵尸行**，且无人回收。

**裁决（主人拍板）：走 B 方案——retry 只把票改回可写，attempt 由 claim-turn 时 `MAX+1` 自然长出。**
连带结论：`contribution_attempt.READY` **永久无产生路径**（D-16 曾猜它留给 L18，猜错了）。

判据用的是项目自己已确立的三条：
- L15「不变量由**数据的存在性**保证」——不建那一行，就不可能有僵尸；
- L16「状态要**如实反映**有没有一棒正在进行」；
- L17「**状态不准是会骗人的**」——一行 `READY` attempt 在时间线上显示成什么都是假的。

### P3 🔴 `session.status` 是一个**只写不读**的状态（本课最大的发现）

核实结果：在 collaboration 模块里 `session.status` **被写 4 处、被读来做判定 0 处**，没有任何 SQL 的 WHERE 用到它。

这解释了 L16 那个 bug 为什么能潜伏两课：`SessionStatus.java:17` 与 `SubmitContributionService.java:147` 都白纸黑字写着「等下一棒 claim lease 时再回到 RUNNING」，但 `ClaimLeaseService` 里**只判了 `OPEN`**——那个「再回到」从来没有被实现过。

> **L17 说「状态不准是会骗人的」。这里是更狠的后半截：
> 一个从来没被读过的状态，连骗人的机会都没有——它只是静静地错着，直到有人把它显示出来。**

而 L18 的时间线页正是它的**第一个真正消费者**，stop 的闸门是它**第一次承担判定职责**。在它变成"守卫"之前，必须先修掉这个 bug。

### P4 蓝图状态机的一条边画错了

`ACPP状态机.md` 写 `BLOCKED_BY_PREDECESSOR --> READY_TO_WRITE: 前序恢复`——**把两步合成了一步**。「前序恢复」有两种含义：

| 情形 | 正确的目标态 | 谁负责 |
|---|---|---|
| 前序**被 retry 了**（可写但还没写） | `WAITING_PREDECESSOR` | 本课 retry |
| 前序**真的写完了**（DONE） | `READY_TO_WRITE` | L16 `wakeSuccessor` |

若 retry 直接解冻到 `READY_TO_WRITE`，第 3 棒会在第 2 棒还没重写完时就开始写，基于一篇缺了一块的文章续写。

### P5 🔴 UX 规格的「可查看下一棒尾令牌」**物理上做不到**

库里只存 `HMAC-SHA256(pepper, 明文)`，**摘要算不回明文**。L16 已为这条铁律付过一次代价（D-16：submit 响应的 `nextHandoffToken` 恒为 null）。

⇒ **判据：当 UX 需求撞上安全模型时，往往不是砍需求，而是换一个能满足它的机制。** 主人真正要的是「我能拿到一根可用的令牌」，不是「我要看那一根特定的令牌」。
⇒ 实现成「**重新签发**」：吊销旧的 + 生成新的 + 明文显示一次。这也正是 D-16 遗留第 3 条的落点。

### P6 三笔"注释承诺了、代码没做"的旧账

| # | 欠账 | 谁承诺的 |
|---|---|---|
| ① | session 永远回不到 `RUNNING` | `SessionStatus.java:17` + `SubmitContributionService.java:147` |
| ② | `COLLAB_STARTED`/`HANDOFF_CLAIMED`/`LEASE_CLAIMED` 三个动作无人产生 | `V014` 的 CHECK 值集 |
| ③ | `TicketStatusView.errorReportId` 恒为 null | `TicketStatusView.java:23`「L17 才会有值」 |

⇒ **教训：注释是承诺，但没有任何机制保证它被兑现。测试只测「代码做了什么」，测不出「代码答应了却没做什么」。**

## 决策

### 决策 1 · V015：只加状态值，不加表

`ck_ticket_status` +`CANCELLED`、`ck_audit_action_type` +5 个 L18 动作。

**`CANCELLED` 存在的理由不是"状态机好看"，是让机娘停下来**：收工后若不改票状态，第 3 棒的机娘跑着 `collab wait` 查到票仍是 `WAITING_PREDECESSOR`，服务端答「5 秒后再来问」——它会等一个永远不来的信号直到 900 秒超时。那句话是真的，但真相是「协作已经收工了」。

靠 session 兜底行不行？那要求**每一个读票的地方都记得 join session**，而每写一个新查询都可能忘记。同 L15 判据：**不变量由数据的存在性保证，不由每个查询记得过滤来保证**。

### 决策 2 · 协作只有**一个出口**（主人推翻蓝图）

蓝图画了两条出边（正常收工 → `READY_FOR_OWNER_REVIEW`、出错放弃 → `TERMINATED`）。主人的论证：

> 两者对**草稿**而言结果完全相同——协作不再占着它。至于内容是删是留，那是草稿模块（L19）与删除功能的事，**不该由 terminate 回答**。

★ 判据：**别让一个机制回答两个问题。**（与他在 L16 否决 handoff TTL 是同一条。）
额外收益：主人少做一次选择，而那次选择他**此刻根本没法做**——他还没看内容呢。

⚠️ 边界：首棒失败（`INVALIDATED`）不归它管——那时 post/draft 从未创建，没有草稿要解锁。

### 决策 3 · 闸门选在哪个对象上——三个动作三个答案

| 动作 | 判定的是 | 闸门落在 |
|---|---|---|
| retry | 这张**票**是不是超时了 | `ticket.status='FAILED_TIMEOUT'` |
| 结束协作 | 这条**协作**是不是还在跑 | `session.status IN (...)` |
| 重新签发 | 那根**尾令牌**还在不在 | `handoff.status IN ('AVAILABLE','FROZEN')` |

★ **判据：闸门要选那个「能唯一代表这次操作发生过」的对象。**

反例说明为什么：重签若拿 session 当闸门，session 状态在重签前后**根本不变**，记不住"重签发生过"，两个并发请求会双双通过、签出两根令牌。stop 若拿某一张票当闸门——拿哪张？它的操作对象是整条协作。

### 决策 4 · 🔴 跨闸门竞态：retry 必须把 session 条件纳入自己的 WHERE

retry 的闸门在**票**上、stop 的闸门在**会话**上，**两把锁管不同的行、互相拦不住**。若 retry 先 SELECT 一次 session 看到 `PAUSED_ON_ERROR`、随后 stop 提交、retry 才改票，结果就是：协作已收工，却有一张票被改回了「可写」。

⇒ `retryTicket` 的 SQL 用 `JOIN collaboration_session` 把 `cs.status='PAUSED_ON_ERROR'` 写进同一条 WHERE。

★ **判据：当两个操作的闸门落在不同对象上时，必须有一方把对方的条件纳入自己的 WHERE**，否则两把锁各自都"成功"，合起来却破坏了不变量。

### 决策 5 · retry 不需要 `@Idempotent`

**先问「重发会不会造成第二次真实的改变」。**

```text
L16 submit：重发 → 可能写出【两段正文】   ← 真实副作用 ⇒ 需要幂等
L18 retry ：重发 → 闸门返回 0 行，什么都不发生 ⇒ 不需要
```

而且幂等键**由客户端生成**——浏览器点两次是两个不同的 key，幂等根本认不出它们是同一件事。

★ **判据：幂等防的是「同一个请求被重发」，闸门防的是「这件事被重复执行」——不管来的是不是同一个请求。**

### 决策 6 · 解冻**不是**冻结的倒放

| L17 冻的 | retry 怎么解 | 为什么 |
|---|---|---|
| attempt → FAILED_TIMEOUT | 🚫 **不解冻，永久保留** | 它是**历史**（验收栏「错误历史保留」） |
| 死的那张票 | → `READY_TO_WRITE` | 要能被重新领 |
| 后序整条尾巴 | → **`WAITING_PREDECESSOR`** | ⚠️ 不是可写——前一棒还没重写完 |
| 尾令牌 | → `AVAILABLE` | 新人又能排队 |
| 会话 | → `AWAITING_CONTINUATION` | ⚠️ 不是 RUNNING——此刻没人在写 |

三条链表遍历，方向与深度各不相同：

| | 唤醒（L16） | 冻结（L17） | 解冻（L18） |
|---|---|---|---|
| 走几步 | **一步** | **走到底** | **走到底** |
| 目标态 | READY_TO_WRITE | BLOCKED | WAITING_PREDECESSOR |

### 决策 7 · 时间线跨模块只读 → **SQL 投影，不开 Facade**

项目的分工是按「读/写/派生」分的，不是按「跨不跨模块」分的：

| | 怎么跨模块 | 先例 |
|---|---|---|
| **读** | 只读 SQL 投影 | L14 跨模块查作者 |
| **写** | Facade | L16 `ContentFacade`、L17 `CollaborationFacade` |
| **派生行为** | 领域事件 | L17 写 error_report / audit_record |

Facade 的理由是「写要保证事务边界与不变量」。**读不改变任何东西**，为它架 Facade 是拿成本换不存在的收益。而领域事件在这里**根本用不了**——你没法"发个事件然后等它把数据告诉你"。

### 决策 8 · `FailurePropagation` 抽取——被第二个触发者逼出来的

L18 出现了第二个「何时」：机娘自报失败。两者「做什么」完全相同。
⇒ 这验证了 L17 那条判据的价值：**把「做什么」独立出来之后，加一个新触发者不需要复制任何逻辑**——复制出来的两份一定会漂移。

### 决策 9 · 🔴 施工期发现的死锁：`updateById` 的隐藏代价

**症状**：加了 `LEASE_CLAIMED` 事件后，集成测试随机报 `DeadlockLoserDataAccessException`。

**根因两层**：

1. `updateById` 会 SET **全部列**，其中 `owner_user_id`/`planned_channel_id`/`tail_handoff_token_id` 是**三个外键列**。即使值没变，InnoDB 也要做外键检查，给三张父表的行各加一把 **S 锁**。
2. 同时 `AuditListener` 异步插 `audit_record`，它的 **4 个外键**同样要对 `collaboration_session`/`contribution_ticket`/`agent_account` 加 S 锁。两边**加锁顺序相反** → 成环。

**修法两步**：
- 会话状态推进一律改**精准条件 UPDATE**（只 SET 该改的列）；
- **V016 去掉两张流水表的全部外键**——★ **流水表不建外键**（业界惯例）：它们的每一列都由我们自己的代码写入，外键防的"用户传了不存在的 id"根本不会发生，而代价是锁竞争与将来的归档困难。

★ **判据：`updateById` 的代价不是"多写几列"，是"多锁几张表"。**
★ **为什么 L15–L17 没炸：并发缺陷的暴露需要压力，而压力常常由一个看似无关的新功能提供。**

### 决策 10 · CLI `resume` 是**自适应入口**，不是"接收 retry 通知"

我最初的定位是错的。退回 ACPP 出发设计（`V012` 文件头：「两个 AI 对话之间零共享上下文，**主人是唯一的传递媒介**」）后想清楚：

```text
场景 1  机娘的 wait 还在跑 → retry 后下次轮询就看到了，【不需要通知】
场景 2  wait 已超时退出   → 进程没了
场景 3  对话崩了（典型）  → 进程和上下文全没了
```

> ★ **在最需要通知的那一刻，接收方恰好已经不存在。任何通信机制都推不到一个已死的进程。**

业界给 CLI 做反向通信的标准解法是「客户端建**出站**长连接」（`stripe listen` 即如此，因为 CLI 在 NAT 后面没有公网地址），但那要求进程**常驻**——而机娘是一次一条命令的 AI 对话，挂不住长连接。

⇒ **不上推送**。`resume` 的真正价值是**替机娘省掉「我现在该跑哪条命令」这个判断**（对 AI 客户端特别贵），Skill 文档因此能从"五分支决策树"简化成一句「跑 resume，照它说的做」。
⇒ 「通知」由**网页上的一键复制话术**承担——经由主人，与 L15 接力棒必须经过人手是同一个架构决定。

## 后果

- 后端 **118 绿**（22 测试类，+1 新类 15 条）· CLI **10 绿** · 前端 **23 绿** + type-check/build 通过。
- 协作从此有了完整闭环：开局 → 接力 → 写作 → 失败 → **人的决策** → 恢复 / 收工。
- `session.status` 从"装饰性字段"变成"真正的守卫"（stop 的闸门 + 时间线的展示）。
- 清掉 2 个死状态（`FAILED_CLIENT` 有了产生路径、`READY` 确认永久不用）。
- 新增一条工程铁律：**流水表不建外键**。

## 冻结面登记（DRIFT **D-18**）

| # | 漂移 |
|---|---|
| 1 | 迁移编号 → V015（+ 施工期 V016） |
| 2 | 端点目录**没画** terminate/stop 与重签，本课自定义三个 owner 端点 + 1 个 agent 端点 |
| 3 | `RetryTicketResponse` **删掉必填的 `attemptNo`**（A 方案遗留，无消费者且会误导） |
| 4 | 协作出口由两条边**合并为一条**（`TERMINATED` 不再使用） |
| 5 | 状态机的 `BLOCKED → READY_TO_WRITE` 修正为 `BLOCKED → WAITING_PREDECESSOR` |
| 6 | `attempt.READY` 确认**永久无产生路径**（推翻 D-16 的推测） |
| 7 | UX「查看尾令牌」→ 实现为「重新签发」（安全模型不可让步） |
| 8 | **流水表去外键**（V016，死锁修复） |
| 9 | 时间线端点原标 L17，**实际在 L18 实装** |
| 10 | `TicketStatusView` 补 `attemptNo`、真填 `errorReportId` |

## 遗留待决

| # | 待决 | 评估 |
|---|---|---|
| 1 | **handoff 与 ticket 1:1 + version 覆盖**（主人提案） | **技术可行**：V012 否决理由①（一张令牌关联两个 ticket）在**线性链**前提下可化解——把 1:1 定义成「我之后的那根」，`consumed_ticket_id` 可从 `predecessor_ticket_id` 反查。真代价只有一条：**凭证的生命周期痕迹从"表"降级成"流水"**。不在 L18 做的理由：它动 L15 的地基，而 L18 已经很大；收益是存储层面的，非功能性 |
| 2 | **跳过死掉的一棒**（改因果链指针） | **技术上就是链表删节点，很简单**。代价三条且都可解：`sequence_no` 留洞（重定义 `last_completed_sequence` 即可）、不可逆（`audit_record` 留痕）、第 3 棒写作前提变了（**其实不成立**——它还没写呢）。⚠️ `APPROVAL_RECORD:28` 约束的是 **retry 这个动作**，严格读**并未禁止"跳过"**；真正禁止它的只有 `course_schedule.csv` 的 forbidden 栏，而那一栏的性质是**课程边界**（L16 禁 Worker→L17 做了；L17 禁 retry→L18 做了） |
| 3 | 换机娘接管 | 需先回答一个产品问题：那一棒最终署谁的名 |
| 4 | 续租 / 心跳（`last_heartbeat_at`、`max_lease_expires_at`） | 主人拍板本课不做——它与 retry 是两个主题：retry 管「死了怎么救」，续租管「还活着怎么别被误杀」 |
