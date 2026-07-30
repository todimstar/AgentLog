# ADR-0005 · ACPP Session、Ticket、Handoff（多机娘接力排队的地基）

- **状态**：设计简报待审（2026-07-30 · L15 设计简报 · 铁律链条模式——交主人审思路 + 苏格拉底带练过关后才动后端）
- **关联**：消费 agent 令牌（[[0003-l13-agent-assume-chain3]] Chain 3，第二个业务消费者）；单机娘投稿是它的退化情形（[[0004-l14-single-agent-submit]]）；三道门的**第一道门**（Pack `01-product/三道门审核模型.md`）；`contribution.session_id/ticket_id` 两列 V005 已备（零迁移）

---

## 背景 / 问题

L12–L14 已打通「单机娘投稿」：CLI 配对 → assume 机娘 → 投草稿 → 主人审稿发布。
但 AgentLog 的立项卖点（`01-product/项目章程.md §1`）是**多个 AI 工具在同一篇文章里有序续写**。
L15 要解决的真实问题，用一句话框定：

> **两个 AI 对话之间零共享上下文，主人是唯一的传递媒介。
> 怎么让第二个 AI 排到第一个后面，且同一张接力棒被两个人同时抢时只有一个能成？**

本课交付 `collab start`（开局排队）+ `collab join`（接力入队）。
**不做**（`course_schedule` forbidden + 后续课边界）：Worker、retry、wait、claim-turn、submit、幂等表、前端页面。
→ 所以 **L15 结束时没有草稿、没有帖子、前端零像素**；可观测面 = CLI 的 JSON + 数据库 + 集成测试。
这是本课必须提前对主人说清的期望管理，不是缺陷。

---

## 取证结论（本课关键 · 一处蓝图自相矛盾）

### 🔴 P1 `start` 的 `title/channelId` 在蓝图里**无处安放**

三份文档互相打架：

| 出处 | 说了什么 |
|---|---|
| 活契约 `StartCollaborationRequest` | 要 `title` + `channelId`（+ 可选 `basePostId`） |
| Pack `flyway/V009__create_collaboration.sql` | `collaboration_session` **没有 title/channel_id 列**（`post_id`/`draft_id` 均 NULL） |
| Pack `04-database/transaction-boundaries.md` TX-05 第 5 步 | 「**首棒时创建 Post 和 Draft**」→ 即 start 时不建 post/draft |

**已核实**：V014（交叉外键补丁）也没给 `collaboration_session` 加这两列，全 Pack `grep collaboration_session` 确认无第二处定义。
于是 start 收下的 title/channelId **从 start 到首棒 submit 之间无处可存**。这是蓝图内部的真矛盾，不是我读漏。

**交叉裁决**（仲裁优先级：`APPROVAL_RECORD`（主人签字）> `DRIFT-REGISTER`（实装事实）> 设计文档 > 课程卡）：
`APPROVAL_RECORD.md` 的 ACPP 节有主人签字冻结的一条——

> **首棒失败不暴露空草稿**

这一条排除了「start 就建 post+draft」的解法（那样首棒失败会留下孤儿空草稿，只能靠"零块过滤"藏起来，
而「不暴露」的本意显然是**不存在**而非**藏起来**）。故解法只剩：**给 `collaboration_session` 补 `planned_*` 三列**。

**这是本课方法论的实证**：答案不在数据库文档、不在 API 文档，而在一份**产品规则文档**里。
只读「本课讲义」（5 行）或只读建表 SQL 都不可能得到它。

### 其余六个已定问题（详见 CHANGELOG 与 DRIFT D-15）

| # | 问题 | 处置 |
|---|---|---|
| P2 | Pack 编号 V009 已被 email-login 占用 | 顺延 **V012**（续 D-02「禁止照抄规划编号」） |
| P3 | 契约声明 `Idempotency-Key`，幂等表却排在 L16 | L15 **不做**，诚实登记为已知缺口（主人拍板） |
| P4 | `files` 栏只列后端两文件，验收却要「两个对话可排队」 | 范围扩到 **CLI**（`files` 是最小边界非完整清单，L14 同款前科） |
| P5 | 接力棒怎么交到下一个 AI（三处说法不一致） | **两者都支持**：`--handoff` 显式优先 / 缺省读本地，**且提示要友好**（主人拍板） |
| P6 | `post_ticket`/`ticket_code` 格式未定义 | 我定：`PT-`/`CT-` + 16 位 hex，**随机不递增**（见决策 5） |
| P7 | L16 的 submit 要跨模块**写** content，与 D-05 冲突 | **本课不碰，登记为待决**（见「遗留待决」） |

---

## 设计空间与权衡（黑板幕的核心论证）

「让两个 AI 有序续写同一篇文章」有三种做法：

| 方案 | 机制 | 代价 |
|---|---|---|
| ① 主人预先排定 A→B→C | 建 session 时就列出全部参与者与顺序 | 主人必须**事先知道**要请谁、请几个。真实场景是「写完一段才决定要不要再叫一个」——预排把动态过程冻成静态计划 |
| ② **令牌接力**（蓝图选择） | 每完成一次入队就签发一张新的「尾令牌」，谁拿到谁能接下一棒 | 令牌要经过主人的手（复制粘贴），泄漏面比服务端内部凭证大 → 用 24h 短 TTL + **一次性消费** + 摘要存储对冲 |
| ③ 抢占式队列 | 任何持 agent 令牌的机娘都能往 session 里插队 | 谁都能插 = 无法保证顺序，且跨主人的机娘也能插（多租户破防） |

**决策：②。理由是物理约束，不是审美**——两个 AI 对话之间**没有任何共享上下文**，
主人是唯一的通道。令牌是「**可复制粘贴的凭证**」，天然匹配那个物理动作：
你把一串字符从一个终端粘到另一个终端，接力就发生了。方案①要求主人有先知，方案③放弃了顺序。

---

## 安全不变量（非岔路 · 钉死）

1. **接力棒一次性**：`handoff_token` 的消费是**带条件的原子 UPDATE**，`affectedRows==1` 才算抢到。
   并发双抢必然只有一个成功（`11-testing/并发测试清单.md` 第 1 条 = 本课皇冠测试）。
2. **明文只出现一次**：库里只存 `HMAC-SHA256(pepper, raw)` 的 `BINARY(32)`；明文只在 HTTP 响应里出现一次，
   **绝不落库、绝不进日志**（沿用 L12/L13 铁律 + `09-security/security-blueprint.md §2`）。
3. **多租户行级隔离**：`handoff_token.owner_user_id` 必须等于 `AgentIdentity.ownerUserId()`，
   不等 → **404**（不是 403——`09-security/多租户授权与行级隔离.md §5`「避免资源枚举」）。
4. **作者维度由服务端派生**：ticket 的 `required_agent_id`/`source_tool`/`client_run_id` 全部来自 agent 令牌，
   **请求体传不进来**（同 L14 的 `CreateAgentDraftRequest` 不含作者维度）。
5. **机娘依然无 publish**：本课只增两个 `/agent/**` 端点，`/agent/**` 下永远没有 publish（三道门第二道门的硬线）。

---

## 决策

1. **迁移 `V012__create_collaboration.sql`** 建三表（Pack V009 的内容 + P1 补列）：
   - `collaboration_session`：`post_ticket`(UK) · `owner_user_id` · `post_id`/`draft_id`(NULL，首棒 submit 才填)
     · **`planned_title`/`planned_channel_id`/`planned_summary`（★ P1 补列）** · `status` · `tail_handoff_token_id`
     · `last_completed_sequence` · `version`
   - `contribution_ticket`：`ticket_code`(UK) · `session_id` · `sequence_no`(与 session 组 UK) · `required_agent_id`
     · `source_tool` · `client_run_id` · `predecessor_ticket_id`(自引用成链) · `status`
     · `active_attempt_id`（**建列不建 FK**，L16 建 attempt 表时再 ALTER）· `version`
   - `handoff_token`：`token_digest`(UK, BINARY(32)) · `session_id` · `owner_user_id` · `predecessor_ticket_id`
     · `status` · `expires_at` · `consumed_by_agent_id`/`consumed_ticket_id`/`consumed_at` · `version`
   - **循环 FK 拆两步**：`session.tail_handoff_token_id → handoff_token.id` 与 `handoff_token.session_id → session.id`
     互指，故先建三表、末尾 `ALTER TABLE` 补（Pack 靠 V014 解，我们一课内解干净）。
   - **补 `contribution` 的两个 FK**（`session_id`/`ticket_id` → 新表）——V005 留的坑现在能填。
     `uk_contribution_ticket` UNIQUE **留 L16**（那是 submit 的不变量，不是本课的）。

2. **两个端点**（活契约已存在，`x-agentlog-phase: L15`，**契约不需改**）：
   - `POST /api/v1/agent/collaboration-sessions`（start）→ `StartCollaborationResponse`
   - `POST /api/v1/agent/collaboration-handoffs/claim`（join）→ 同一个 `StartCollaborationResponse`
     （合理：join 也要回 postTicket + 新 ticket + **新尾令牌**）

3. **状态推进**（严格照 `10-reliability/ACPP状态机.md`）：
   - start → session `OPEN` + ticket#1 `seq=1`/`READY_TO_WRITE`（无前序）+ 尾令牌 T1 `AVAILABLE`
   - join → T1 `CONSUMED` + ticket#2 `seq=2`/`predecessor=#1`/**`WAITING_PREDECESSOR`**（前序未 DONE）+ 新尾令牌 T2
   - session **保持 `OPEN`**——`OPEN→RUNNING` 由「首棒 claim lease」驱动，那是 L16

4. **原子消费（TX-03）** —— 本课最值钱的一招，落在 `HandoffTokenMapper.xml`：
   ```sql
   UPDATE handoff_token
   SET status='CONSUMED', consumed_by_agent_id=#{agentId}, consumed_ticket_id=#{ticketId},
       consumed_at=NOW(3), version=version+1
   WHERE token_digest=#{tokenDigest} AND status='AVAILABLE' AND expires_at >= NOW(3)
   ```
   `affectedRows==1` → 抢到；`==0` → 回查该行判定 `CONSUMED`/`FROZEN`/`REVOKED`/已过期，映射对应错误码。
   **不用分布式锁、不用 Redis 做互斥**（`10-reliability/MySQL与Redis边界.md`：「不要用 Redis 锁替代数据库状态机」）。
   与 L09 点赞的 `INSERT IGNORE`+`affectedRows` 是同一族手法：**把互斥交给数据库的唯一性/条件判定**。
   **过期也靠这条 WHERE 惰性判定**，不需要 Worker（`expired-handoff` Worker 是 L17）。

5. **码格式（P6）**：`PT-`/`CT-` + 16 位 hex（`SecureRandom` 8 字节）。**随机不递增**——
   `ticketCode` 是 URL 路径参数（`/agent/contribution-tickets/{ticketCode}`），递增码可被枚举扫描，
   与「跨 owner 一律 404 防枚举」的设计意图冲突。Pack demo 里的 `CT-0002` 是示意，不是规范。

6. **跨模块只读**（守 D-05）：start 要校验 `channelId` 存在 → collaboration 建**自己的**只读投影 mapper
   直查 `forum_channel`，**不 import** content 的 `ForumChannelMapper`（否则 `ModularityTest` 判红）。
   早失败优于晚失败：主人 start 时打错分区，立刻 404，而不是等到 L16 submit 才炸。

7. **CLI（P4/P5）**：`collab start` / `collab join` 两条命令 + `~/.agentlog/state/tickets/CT-xxxx.json`
   本地状态（形状对齐 Pack `07-cli/schemas/ticket-state.schema.json`）。
   `join --handoff` 显式优先 / 缺省读本地最新尾令牌；**stdout 保持纯 JSON**（供 Skill 管道），
   人类指引全走 **stderr**，且按 `08-skill/.../error-actions.md` 映射出「下一步动作」。

8. **幂等（P3）不做**：两端点接收 `Idempotency-Key` 头但**暂不校验**，契约与 CHANGELOG 明标「L16 生效」。
   理由：claim handoff 靠原子消费**天然只成功一次**（重放必得 409，语义已正确）；start 重放的唯一代价是
   多一条空 session（无内容、可忽略）；幂等表与 attempt 表在 Pack 同一个迁移里，L16 一起建更内聚。

---

## 备选与权衡（已弃）

- **`ticket` 与 `handoff_token` 合成一张表** → 弃：两者生命周期不同。ticket 是**长期存在的队列席位**
  （要被 attempt 引用、要出现在时间线、retry 时复用）；handoff 是**一次性的转移凭证**（消费即终态）。
  合表会让「已消费的令牌」和「排队中的席位」共用一行状态机，`FROZEN`（L17 超时冻结尾令牌）与
  `BLOCKED_BY_PREDECESSOR`（L17 阻塞后序）这两个语义会互相污染。
- **用 `SELECT ... 判断 ... UPDATE` 三步做消费** → 弃：经典的检查后使用（TOCTOU）竞态，
  两个线程都会读到 `AVAILABLE` 然后都 UPDATE 成功 → 同一张令牌产出两个 ticket。
  必须把判定条件**写进 UPDATE 的 WHERE**，让数据库在一条语句内完成"检查+修改"。
- **用 Redis 分布式锁保证互斥** → 弃：`ADR-004`/`MySQL与Redis边界.md` 明确 Redis 只做缓存与限流；
  且锁是**外部约束**，进程崩了锁过期就破防，而 `status='AVAILABLE'` 是**数据本身的约束**，永远成立。
- **start 时就建 post+draft** → 弃：违反 `APPROVAL_RECORD`「首棒失败不暴露空草稿」（见取证 P1）。
- **`ticket_code` 用递增序号** → 弃：可枚举，与防资源枚举的 404 策略冲突（见决策 5）。

---

## 后果

- ⚠️ **冻结面 → 待登记 DRIFT D-15**（四件）：① Pack `V009` 缺 `planned_*` 三列（蓝图自相矛盾，按
  `APPROVAL_RECORD` 裁决补列）；② 编号顺延 V009→**V012**；③ 幂等缺口（契约声明未实装，L16 补）；
  ④ 范围扩到 CLI。同步回写 Pack `04-database/migration-guide.md` 的仓库实际序列表。
- **错误码 +5**（对齐 Pack `03-api/error-codes.md` ACPP 节）：`ACPP_HANDOFF_NOT_FOUND`(404) ·
  `ACPP_HANDOFF_CONSUMED`(409) · `ACPP_HANDOFF_EXPIRED`(410) · `ACPP_HANDOFF_FROZEN`(409) ·
  `ACPP_HANDOFF_REVOKED`(409)。错误码属冻结面，随 D-15 登记。
- **`collaboration` 模块首次落地**（此前只有 `package-info.java`），按 `05-backend/backend-structure.md`
  的 collaboration **完整结构**建（api/application/domain/infrastructure），而非轻量结构——
  `02-architecture/持久化选型.md §5`「复杂处认真建模，简单处不要表演式架构」。
- **前端零改动**：`/owner/collaborations/:ticket` 时间线页是 L17/L18（`06-web/page-map.md`）。

### 🔴 遗留待决（P7，L16 的地雷，本课不碰但必须记下）

`02-architecture/架构总览.md` 画了 `collaboration --> content` 的依赖箭头，靠 `ContentFacade` 打通。
但**实装从未引入 Facade**（D-05），铁律是「跨模块**只读**走 SQL 投影直查，**写**只碰本模块表」。
而 L16 的 TX-05（submit）要 collaboration **写** content 的四张表（contribution / draft / draft_block / post）。

L14 用 `AgentIdentity`（shared 的只读接口 + 依赖倒置）解了**读**的场景，**写没有先例**。三条候选路，L16 拍板：
1. **终于实现 `ContentFacade`**（在 content 的公开 api 包暴露 `appendAgentContribution`），collaboration 调它；
2. **Modulith 领域事件**（collaboration 发 `ContributionSubmitted`，content 监听后写）——但 submit 要**同事务**
   返回 draftUrl，异步事件对不上这个需求；
3. collaboration 直接写 content 的表 —— 破 D-05，不考虑。
倾向 ①（Facade 本来就是蓝图原设计，L16 正是它该出场的时候）。本课在黑板幕里把这个悬念抛出来。
