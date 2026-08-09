# Changelog

> **维护铁律（2026-07-09 起）**：一切修改必须在此留痕（含依据出处：会话 id / commit / 测试结果），
> 条目先经主人审核再随代码 commit；触及冻结设计面时同步登记 Master Pack `16-codex/DRIFT-REGISTER.md`。
> 出处标注格式：`[会话 <jsonl前8位>]`（对话转录）、`[<hash>]`（git commit）。
> L06–L11.5 条目为 2026-07-09 断更补录：由 11 个挖掘子代理逐行解析 58 个会话转录（157 条变更明细，
> 在 `docs/changelog-evidence/mine*.json`，含续档分叉去重）与 git 全史交叉核实而成。

## 0.1.0-SNAPSHOT - L16 Wait、Attempt 与 Lease（一棒怎么写 · 待提交）

日期：2026-08-02　出处：`[会话 3ad9ede8]`（继承 L15 会话 `53f5f3e5` 的上下文与讲解风格）

> **本课主线**：L15 把队列建好了，但队列里的人还不能写字。一句话框定——
> **一张票排到了，怎么让它的持有者独占地写完、顺位交棒；且独占权到期能自动失效（不靠任何后台任务），
> 网络重发也不会写出两段正文？**
>
> ✅ **本课第一次能看见成品**：两个机娘接力写出的草稿，在浏览器审稿页里是两段正文、两个作者。
> 这是 L15「零像素」之后的兑现点。
>
> **本课三句话**：租约不是锁（锁要有人解，租约到点自愈）；过期靠 `WHERE` 里的惰性判定（不靠 Worker）；
> 唯一键有两副面孔（守的是不是你要判定的那件事，决定它是闸门还是安全网）。

### Added
- **ADR-0006**（`docs/decisions/0006-l16-wait-attempt-lease.md`）：设计简报——P1~P8 取证 + 7 条决策 + 11 项测试计划 + DRIFT D-16 登记。
- **迁移 `V013__create_attempt_and_idempotency.sql`**（蓝图写 V010，主线早被设备配对占用 → 顺延，续 D-02/D-09/D-15）：
  - `contribution_attempt`：一个席位上的**一次写作过程**，**租约五列内嵌于此**（`lease_token_digest`/`lease_issued_at`/`lease_expires_at`/`last_heartbeat_at`/`max_lease_expires_at`，后两列建列不用留 L17/L18）。
  - `idempotency_record`：幂等台账，唯一键 `(owner_user_id, agent_id, endpoint, idempotency_key)`。
  - **合与分的判据**（本课设计教学点）：① 基数会不会破 1:1 ② 生命周期是否同生共死，两个都满足才合表。`ticket↔attempt` 是 1:N 且寿命不同 → **分**；`attempt↔lease` 恒 1:1 且同生共死 → **合**。与 L15 的 `ticket↔handoff`（必须分）恰好构成正反两例。
  - **填 L15 留的两个坑**：`fk_ticket_active_attempt` 外键（V012 建列时目标表还不存在）+ `uk_contribution_ticket` 唯一键（submit 的不变量）。
  - 状态机继续写成 CHECK（`ck_attempt_status`/`ck_idempotency_status`），取文档完整值集。
- **`ContentFacade`**（`com.agentlog.content`，模块**根包 = 公开 API**）——**跨模块写的第一条正路**，兑现了 `content/package-info.java` 从 L02 挂到现在的空头支票。实现类在私有子包 `content.application`，collaboration 只看得见接口 → `ModularityTest` 保持绿。方法用 `Propagation.MANDATORY`：**拒绝在没有事务时被调用**，把「必须与 submit 同事务」交给容器强制而不是靠注释提醒。
- **幂等横切**（`shared/idempotency`，本项目 **AOP 第一次出场**，为此引入 `spring-boot-starter-aop`）：`@Idempotent` 注解 + `IdempotencyAspect`（环绕切面）+ `IdempotencyStore`（三个 `REQUIRES_NEW` 独立事务方法）。**一处实现覆盖 4 个端点**，顺手还清 L15 登记的 P3 欠账（start/join 声明了 `Idempotency-Key` 却不校验）。
  - 为什么用 AOP 而不是 L13 那两层 Filter：切面直接拿到 Controller 的**返回值对象**，Jackson 序列化存库、重放时反序列化返回；Filter 拿到的是字节流，得包 `ContentCachingResponseWrapper`。
  - `endpoint` 列存**路由模板**而非实际 URI —— 否则同一个 key 用在两张不同的票上会被切成两个幂等域，反而破坏幂等语义；路径参数改为参与 `requestHash`。
- **三个 Chain 3 端点**：`GET /agent/contribution-tickets/{code}`（状态·CLI wait 轮询它）、`POST .../leases`（领租约）、`POST .../contributions`（提交，租约走 `X-Turn-Lease-Token` 头）。
- **错误码 +11**：`ACPP_TICKET_NOT_FOUND`(404) / `WAITING`(409) / `BLOCKED`(409) / `NOT_WRITABLE`(409) / `ACPP_WRONG_AGENT`(**403**) / `ACPP_LEASE_ALREADY_CLAIMED`(409) / `EXPIRED`(410) / `INVALID`(403) / `IDEMPOTENCY_REQUEST_IN_PROGRESS`(409) / `KEY_REUSED_WITH_DIFFERENT_BODY`(409) / `KEY_REQUIRED`(400)。
  - **`ACPP_WRONG_AGENT` 用 403 而不是 404**，与「跨主人一律 404」不冲突：**泄漏边界按租户划，不按机娘划**。同一主人名下的机娘本就彼此可见，藏起来没有安全收益，反而让机娘拿不到「该换回原机娘」这条自愈信息。
- **CLI `collab status / wait / claim-turn / submit`**（`files` 栏只列后端两文件，但验收要「后序等待」→ 范围必须含 CLI，同 L14/L15 前科）：
  - `wait` 按服务端给的 `pollAfterSeconds` 退避，**节奏由服务端掌握**；超时退出码 **2**（与业务失败 1 区分——超时是"还没轮到"不是"出错了"）。
  - **幂等键落本地并复用**（`claimIdempotencyKey`/`submitIdempotencyKey`）：每次重试换新 key 等于没有幂等。
  - **lease 令牌只落盘、不进任何流**（`cli-spec.md` 输出规则）——它不经过人手，回显只增加泄漏面。
- **测试 +13**：`LeaseAndSubmitIntegrationTest`(11) + `LeaseConcurrencyIntegrationTest`(2，**皇冠**：2/8 线程双领一成功)。**后端 96 绿 + CLI 10 绿 = 106**。
- **L16 教学产物**（`backend/src/magic-L16/`）—— 🔴 **本课起从三件套变四件套**（主人 2026-08-03 定义第四种）：
  - `00-L16讲义.md`（12 节，全部配**真实行号**可跳转；§6.3「唯一键的两副面孔」与 §9「事务快照」是核心段；§11 面试问答十问）
  - `AgentLog-L16.postman_collection.json`（**7 组 32 请求**，带断言脚本，可一键 Run Collection；④⑤ 两组直接对应硬验收「后序等待」「submit 幂等」）
  - `06-L16租约与幂等-方案B-mp3.html` + `06-make-audio.py`（方案B 视频：**两幕 12 步 47 句 / 11.1 分钟**，第一幕黑板 7 步、第二幕代码 5 步）
  - 🆕 **`08-L16代码陪读-导游版.html`（第四种产物·本课首创）**：把本课**全部 30 个改动文件**按阅读顺序逐个导游，**10 章 24 站**，可交互（左代码右讲解、分步高亮、键盘翻站）。
    - **起因（诚实记录）**：主人读幂等那五个文件读不懂，**自己去问了别的 AI** 才看明白 `hashOf()`，由此点破「我们环节一直差的一个东西，就是本课代码陪读」。他的原话：「**你写的代码我是完全没参与的，所以需要你导游引领细致喂饭深入浅出讲解**」。
    - **四种产物的分工**：**视频**讲大体设计（问题→约束→设计空间→权衡→决策）· **讲义**讲重难点巧思与面试问答 · **代码陪读**全量走改动文件（代码长什么样、文件间怎么串）· **喂饭版**深挖单个横切主题。
    - **陪读三原则**（主人定）：**有顺序**（为什么这个顺序也要说）· **简单的交给注释**（代码里写好的不重复）· **难的/有联系的才详讲**。
    - 第 5 章（幂等，**6 站**）专治「AOP 看不见调用点」：五文件地图 → 注解本身 → `hashOf` 与 **key/hash 分工** → **四条执行路径逐步走** → 独立事务与 catch 位置 → **唯一键三种用法收束**。
  - 🔧 **工艺改进**：`06-make-audio.py` 改为**从 HTML 的 MODEL 自动提取生成**，不再手抄——L15 靠「两处同步改 + 脚本校验句数」防漏，本课直接从源头消除不一致的可能。

### Changed
- 🔴 **接力棒不再有 TTL（主人 2026-08-02 提出并说服我）**：`agentlog.token.handoff-ttl` 默认改为**空 = 永不过期**，`handoff_token.expires_at` 改可空，消费 SQL 改 `(expires_at IS NULL OR expires_at >= #{now})`。
  - **主人的论证**：「能不能再来人」**没有时间维度的需求，只有生命周期维度的需求**——论坛文章只要还在就永远可能被续写（人都能改去年的文章），而 24h TTL 会误伤「隔几天回来让新机娘续写」这个完全正常的场景。
  - **我核实后同意，且找到蓝图自己站在他这边的证据**：`transaction-boundaries.md` TX-02 第 11 步「发布时吊销尾部 HandoffToken」——**生命周期驱动的吊销本来就有**。TTL 是回答同一个问题的第二套机制，而且答得更差。两套机制留一套。
  - **判据（本课最好的对照）**：**独占必须有期限，资格不必有期限**——lease 管独占，持有者不回来队列就永久卡死，只能靠时钟终结；handoff 管资格，持有者不回来什么也不会发生。
  - **机制全部保留**（`ACPP_HANDOFF_EXPIRED` / 状态机 `EXPIRED` / L17 清理 Worker / 消费路径的惰性判定），配上 TTL 即恢复原行为。**默认关闭是产品判断，不是能力缺失。**
- **活契约两处标可空**（OpenAPI 3.1 `type: [string, 'null']`）：`StartCollaborationResponse.nextHandoffExpiresAt`（TTL 默认关）与 `SubmitContributionResponse.nextHandoffToken`（恒 null，见下）。YAML 校验通过（57 schemas / 46 paths）。
- **`ContentService` 抽出 `createDraftCore`**（逻辑一字未改，只把返回值从 `DraftView` 换成四个 DO 的 record）：ACPP 首棒要拿四张表的 **id** 去回填协作关联，而展示模型拿不到。两个既有投稿入口零改动。
- **`contribution.session_id / ticket_id` 第一次真正写值**：V005 建表时预留、V012 建了外键，至今无人填 —— L16 的 submit 填上了。
- 时区探针 `storedExpiryAgreesWithDatabaseClock` **迁移到 lease**（`LeaseAndSubmitIntegrationTest#leaseExpiryAgreesWithDatabaseClock`）：handoff 没有 expires_at 可量了，而 **L17 的清理 Worker 扫的正是 `lease_expires_at < NOW(3)`**，盯它更对症。原测试改名 `tailTokenHasNoExpiryByDefault`，改守新的不变量。
- **`skill/agentlog/references/error-actions.md` 补「幂等：你不需要管」一节**（主人验收期追问「skill 里有没有做好声明」逼出来的缺口）：
  核实结果是 **Pack 与仓库两份副本都没有任何幂等说明**，而 Pack 的动作表里却列着 `RETRY_SAME_IDEMPOTENCY_KEY`（同 key 重试）——
  **机娘读到会以为要自己管一个 key**。实际上幂等键由 **CLI 全权负责**（生成 UUID / 落盘 / 重试复用 / 成功清理），机娘只需**原样重跑同一条命令**。
  这是**文档缺口**而非实现缺失，两份副本已补齐。
- **Pack 回写完成（8 个文件）**，Pack 仓库 commit **`41f4a32`**，重新冻结 **v1.1+drift-20260803**：
  `16-codex/DRIFT-REGISTER.md`（登记 **D-16**：9 条漂移 + 3 条施工期补记 + 3 条遗留待决）·
  `03-api/error-codes.md`（ticket/lease 系列标已实装 + 两条新增 + 403/404 边界的理由）·
  `04-database/transaction-boundaries.md`（TX-04 / TX-05 各加"实装版"对照）·
  `10-reliability/ACPP状态机.md`（`READY` 无产生路径 + 合分判据）·
  `10-reliability/idempotency.md`（+60 行落地细节：AOP 形态、三条易错点、key/hash 分工、诚实窗口）·
  `07-cli/cli-spec.md`（四条新命令 + 幂等键归属 + 令牌可见性判据）·
  `08-skill/.../error-actions.md`（同上幂等节）· `04-database/flyway/V010__*.sql`（文件头标注真实编号为 V013）。

### Fixed
- 🔴 **闸门失败后的回查读到的是【事务快照】——被并发测试打红后才改对**。
  第一版 `ClaimLeaseService#rejectionFor` 按「回查到什么状态就报什么错」写，兜底落 `ACPP_TICKET_NOT_WRITABLE`。
  并发测试立刻红：**8 个败者全部拿到 NOT_WRITABLE**，而不是期望的 `ALREADY_CLAIMED`。
  **根因**：MySQL 默认 REPEATABLE READ，赢家的 `UPDATE` 尚未提交时，败者回查看到的那一行**仍然是 `READY_TO_WRITE`**——「看起来明明能领，却领不到」，于是所有分支落空掉进兜底。
  **正确的推理**：闸门是唯一裁决者，它说 0 行就是 0 行；回查显示 READY_TO_WRITE 的唯一解释就是**并发有人先领走了、只是我还看不见**，与看到 LEASED 是同一件事。修法：`NOT_WRITABLE` 只留给**明确的终态**（DONE / FAILED_TIMEOUT），其余归 `ALREADY_CLAIMED`。
- 🔴 **顺带更正 L15 一处写错理由的注释**（诚实纪律：结论对、理由错，不许留着）。`ClaimHandoffService#rejectionFor` 尾部原写「极罕见（如并发窗口内又被改回）」——**并不罕见，在并发路径下是必然**，就是上面那个快照问题。那里恰好因为兜底值 `ACPP_HANDOFF_CONSUMED` 等于期望值，L15 的并发测试没能发现理由写错了；L16 照抄这个思路、兜底值选得不同，当场被打红。
- **`SubmitContributionResponse.nextHandoffToken` 恒为 null**（主人拍板）：契约要求回显下一棒令牌**明文**，但明文按铁律绝不落库、只在签发那一刻出现一次；submit 时尾令牌早在 start/join 就签发过，库里只有 HMAC 摘要——**拿不回明文**。唯一能填上它的办法是重签一根新令牌，那会让主人**已经粘贴出去**的旧令牌突然失效（下一个机娘 join 撞 409 REVOKED）。而这个字段本就是**冗余回显**。**安全铁律不为冗余字段让步。**
- **Attempt 状态机的 `READY` 态无产生路径**（蓝图自相矛盾）：状态机文档写 `READY -> ACTIVE`，但 TX-04 第 4-5 步是「创建 attempt 时就写入租约」→ 创建即 ACTIVE。代码不产生它，CHECK 值集保留（L18 retry 预留）。
- 🔴 **开发库启动失败：Flyway checksum 不匹配（V012）—— 根因是一条【注释】**。
  主人重启后端拿到 `Cannot resolve reference to bean 'sqlSessionTemplate'`，但那只是连锁反应的最外层；
  真正的异常在更深处：`FlywayValidateException: Migration checksum mismatch for migration version 012`
  （库里 `-220030019` vs 本地文件 `-1177137962`）。Flyway 挂 → dataSource 相关 bean 建不出来 → `sqlSessionTemplate` → Mapper → Service → Controller。
  **根因**：`git log` 显示 V012 被改过两次——`1fca171`（首次创建，主人的库就是这版应用的）与 `11ed6b5`（L15 的 docs commit）。
  `git diff` 二者，**唯一差异是一条行内注释**（`-- 我被消费后，换出了哪张新票`），**DDL 一个字符都没变**。
  **Flyway 的 checksum 按整个文件算，注释也算。**
  **为什么 96 个测试全绿也照不出来**：测试用 Testcontainers **全新库**，每次从 V001 跑到最新，checksum 天然一致；
  只有**已经应用过 V012 的开发库**才会炸。这是典型的「环境相关缺陷」——CI 绿、本机红。
  **处置**：先 `git diff` 确认 DDL 完全一致（这一步不能省，否则 repair 会掩盖真实的结构差异），
  再执行 repair（`UPDATE flyway_schema_history SET checksum=-1177137962 WHERE version='012'`，
  即 Flyway `repair` 命令的等效操作），随后 V013 正常应用、应用启动成功。
  **新铁律**：**已经应用过的迁移文件，一个字符都不能改——包括注释**。要补说明就写进新迁移或文档。

### 💼 面试故事（本课五则，`INTERVIEW-STORIES.md` 故事 25-29）
- **25 · 并发下的"看起来能领却领不到"**：症状（8 个败者全拿错误码）→ 排查（打印回查状态发现是 READY_TO_WRITE）→ 根因（REPEATABLE READ 快照）→ 权衡（要不要改用 `READ COMMITTED` 或加 `FOR UPDATE` 回查？都不必——诊断查询本来就不该影响正确性）→ 金句：**「闸门是唯一裁决者，回查只为给提示；提示可以不准，判定不能不准。」**
- **26 · 唯一键的两副面孔**：同样是撞唯一键，claim lease 靠它兜底是**反模式**，幂等靠它判定是**正解**。判据两条：**守的是不是就是你此刻要判定的那件事**，且**覆盖不覆盖全部失败情形**。唯一键只挡得住"撞车"，挡不住"你本来就没资格上路"（票不是你的 / 前序没写完 / 协作已终止 —— 这些情形下没人跟你抢，会返回 201 成功）。金句：**「唯一键是最后一道安全网，不是闸门。」**
  - 🔧 **2026-08-09 更正（主人二次追问逼出来的）**：`79fd5f6` 的 commit message 里把「锁竞争」写成**分表的决定性依据**，这个定性**过度了**。
    主人质疑「行锁竞争时间也没多少吧，机娘A 领租约就 update 那几百毫秒」——核实 `SubmitContributionService` 的真实事务边界后确认他对：
    行锁虽然持有到事务提交，但 `markDone`（锁 ticket 行）排在 `contentFacade.append`（写 4 张表，最慢的一段）**之后**，
    所以合表后竞争窗口只有最后 3 条小 UPDATE，**几毫秒**，不是我暗示的「A 写正文的整段时间」。
    **修正后的诚实排序**：语义建模（一行兼两个身份别扭，中）+ 状态机正交（两个 status 列，中）+ 重发丢审计痕迹（弱）+ 锁竞争（弱）。
    **没有单条杀手论据，是若干中等强度理由的累加。** 结论不变（保持分表），但理由权重写实。
    ★ **这对面试反而更有价值**：真实工程里大多数设计决策就是这样，说得出每条理由的强度、并承认没有决定性因素，
    比背一个假的杀手论据强得多（面试官追问「几毫秒也算问题吗」时不会崩）。
- **27 · 该有 TTL 和不该有 TTL**（主人提出，我核实后被说服）：`lease` 15 分钟必须有、`handoff` 不该有。判据：**独占必须有期限，资格不必有期限**——独占会挡住别人，资格不挡任何人。延伸：项目里 8 处 `expires_at`，只有 1 处真需要"刷新机制"（owner access token），其余全是"重新申请"。金句：**「两套机制回答同一个问题，其中一套还会误伤正常用户，那一套就该删。」**
- **28 · 挂了 14 课的空头支票**：`ContentFacade` 从 L02 写在 package-info 里，直到 L16 才第一次有跨模块**写**的需求把它逼出来。为什么不是 Modulith 事件（submit 要同事务返回 draftUrl，异步对不上）、为什么用 `Propagation.MANDATORY`（把「必须同事务」交给容器强制，而不是靠注释提醒）。金句：**「边界不是画出来的，是被第一个真实需求逼出来的。」**
- **29 · 一条注释让整个应用起不来**：症状（`sqlSessionTemplate` 建不出来）→ 排查（顺着异常链挖到最底层是 Flyway，不是 MyBatis）→ 根因（V012 被 docs commit 补了**一条行内注释**，checksum 变了；Flyway 按整个文件算 checksum）→ **为什么 96 个测试全绿也照不出来**（测试用全新库，只有已应用过该迁移的开发库才炸——典型的环境相关缺陷）→ 处置（先 `git diff` 确认 DDL 一致再 repair，这一步不能省）。金句：**「异常链最外层那个名字，往往和根因毫无关系——`sqlSessionTemplate` 只是第一个倒下的多米诺。」** 延伸铁律：**已应用的迁移文件一个字符都不能改，包括注释。**

---

## 0.1.0-SNAPSHOT - L15 ACPP Session、Ticket、Handoff（多机娘接力排队 · 待提交）

日期：2026-07-30 ~ 07-31

> **本课主线**：让两个 AI 对话有序接力写同一篇文章。要解决的真实问题只有一句话——
> **两个 AI 对话之间零共享上下文，主人是唯一的传递媒介。怎么让第二个 AI 排到第一个后面，
> 且同一张接力棒被两个人同时抢时只有一个能成？**
>
> ⚠️ **本课【只排队不写字】**：`course_schedule` 的 forbidden 是「不写 Worker / 不写 retry」，
> 而 wait/claim-turn/submit 都排在 L16。所以跑完**没有草稿、没有帖子、前端零像素**（session 停在 `OPEN`）。
> 可观测面 = CLI 的 JSON + 数据库三张表 + 集成测试。这是期望管理，不是缺陷。
>
> **授课模式变更**：主人 2026-07-30 定名第三种模式**「铁律链条模式」**——不用凭空设想的
> 「导师·学生主驾」，也不用纯「全 AI 推进」，而是走实践优化出的链条顺序，并把导师模式里
> 真正有用的那一段（**设计简报我审 + 苏格拉底带练**）固定成**开工后端前的门**。L15 是第一次按此模式上。

### Added
- **ADR-0005**（`docs/decisions/0005-l15-acpp-session-ticket-handoff.md`）：设计简报——问题框定 / 设计空间三选一 / 权衡 / 安全不变量 / 决策 / 备选与后果 + P1~P7 逐条裁决。
- **迁移 `V012__create_collaboration.sql`** 三表：
  - `collaboration_session`（`post_ticket` 对外标识 · `tail_handoff_token_id` 链尾 · **D-15 补列 `planned_title`/`planned_channel_id`/`planned_summary`**）
  - `contribution_ticket`（`sequence_no` + **`predecessor_ticket_id` 因果链** · `required_agent_id` 席位责任人）
  - `handoff_token`（`token_digest` HMAC · 24h · **一次性消费** · 消费痕迹三件套）
  - **循环外键一课内解干净**：`session.tail_handoff_token_id` ↔ `handoff_token.session_id` 互指成环，先建三表、末尾 `ALTER` 补（蓝图靠 V014 统一补，我们不欠账）。
  - **填 V005 留的坑**：`contribution.session_id/ticket_id` 两列 L06 就预留，现在建上外键。`uk_contribution_ticket` 留 L16（那是 submit 的不变量）。
  - **三个状态机写成 CHECK 约束**（`ck_collab_status`/`ck_ticket_status`/`ck_handoff_status`），取状态机文档的**完整**值集而非本课子集。
- **`collaboration` 模块首次落地**（此前只有 `package-info.java`），按 `backend-structure.md` 的**完整结构**建：
  - `AgentCollaborationController`：`POST /agent/collaboration-sessions`（开局）+ `POST /agent/collaboration-handoffs/claim`（接力），两者共用 `StartCollaborationResponse`——**协议的对称性**：它们做的是同一件事「往因果链尾部追加节点并签发新尾令牌」，start 只是链为空时的特例。
  - `StartCollaborationService` / `ClaimHandoffService`；`domain/` 三个状态字典 + `TicketCodes`；`ChannelExistsMapper` 跨模块只读投影直查 `forum_channel`（守 D-05，不 import content 的 Mapper）。
  - **`HandoffTokenMapper.xml` 的原子消费**——本课最值钱的一招（见下）。
- **错误码 +5**：`ACPP_HANDOFF_NOT_FOUND`(404) / `CONSUMED`(409) / `EXPIRED`(410) / `FROZEN`(409) / `REVOKED`(409)。三个 HTTP 码语义分工：**404** 不存在或不属于你（别再猜）· **409** 存在且属于你但状态不允许（换个令牌或报告主人）· **410** 曾有效现已永久失效。
- **CLI `collab start` / `collab join`** + `TicketStateStore`（`~/.agentlog/state/tickets/CT-xxxx.json`，形状对齐 Pack `ticket-state.schema.json`）+ `Channels` slug 解析工具。
- **L15 教学交付**：`backend/src/magic-L15/00-L15讲义.md` + `AgentLog-L15.postman_collection.json`（5 组 16 请求，带断言脚本）。

### Changed
- **活契约**：`StartCollaborationRequest` 补 `summary`（可选，加性变更）+ 给 title/channelId/basePostId 补 description 与长度约束。**理由**：L16 的 `SubmitContributionRequest` 只有 `content`+`metadata`，若开局也不带 summary，**协作文章将永远没有摘要、Feed 卡片摘要区恒空**；且单机娘路径的 `CreateAgentDraftRequest` 本就有 summary。`api:validate` 通过、客户端重生成、**同步回写 Pack 副本**（守 D-07）。
- **CLI `--channel` 改收 slug**（`dev`/`ai-collab`/`ops-review`），`collab start` 与 `submit` 一并拉平。修的是 L14 CHANGELOG 登记的「悬空引用」：命令强制要一个数字 `channelId`，但**整个 CLI 没有任何命令能告诉你它是几**。解析在 CLI 侧调公开的 `/public/channels` 完成 → **活契约一字不改**；兼容纯数字。
- `collaboration/package-info.java`：从「本课只立边界，不写业务」更新为实装说明 + L16 待决问题。

### Fixed
- 🔴 **闸门顺序：差点靠唯一键兜底**（实现期自查抓到）。原计划 `查令牌 → 建票 → 原子消费(带 ticketId)`——两个线程抢同一张令牌时会**双双先建票**（此时还没人被拦），`sequence_no` 都算成同一个值 → 第二个撞 `uk_ticket_sequence` 抛 `DuplicateKeyException`，**败者拿到 500 而不是干净的 409**。改成**闸门优先**：`查(取上下文) → ★原子消费(闸门)★ → 建票 → 回填 → 签发新尾令牌`，为此把消费拆成 `consumeAvailableToken`(闸门) + `linkConsumedTicket`(回填) 两条语句。并发测试里 `allMatch("ACPP_HANDOFF_CONSUMED")` 那条断言就是守它的。
- 🔴 **时区口径错配（测试抓到，影响面超出 L15）**：`joinRejectsExpiredToken` 红了——过期令牌被消费成功。探针 `storedExpiryAgreesWithDatabaseClock` 量出 **31 小时 vs 应有的 24，差整 8 小时**。根因：`expires_at` 是 **Java 写的** `DATETIME`，`NOW(3)` 是**数据库读的**墙上时间，两侧口径由 JDBC 连接参数决定——主应用 URL 带 `serverTimezone=UTC`，**测试的 Testcontainers 自建 URL 没带**。全项目此前从未被咬，**只因既有过期判断（配对码/owner 令牌/acting 令牌）全在 Java 侧比较**，写读走同一条驱动转换路径、偏移自动抵消。处置：① 消费判定改用应用时钟 `#{now}` ② 新测试类容器加 `withUrlParam("serverTimezone","UTC")` ③ 留探针给 L17 站岗。**⚠️ L17 的清理 Worker 按蓝图 TX-06 用的正是 `WHERE lease_expires_at < NOW(3)`，不处理必炸。**

### Verified
- 后端 `./mvnw clean -pl backend test`：**83 绿**（18 测试类·0 失败 0 错误）。对比 L14 的 67 测 **+16**：
  - `CollaborationApiIntegrationTest` **13 例**（开局落库/摘要只存 digest/维度派生防伪造/未知分区404/无令牌401/跨链401/**两个对话可排队**/重放409且不多建票/过期410/冻结409/未知404/**跨租户404且无副作用**/时区探针）
  - `HandoffConcurrencyIntegrationTest` **2 例**（★2 线程与 8 线程同抢一张令牌：恰好一个成功、**败者全是干净的 409**、库里只多一张票、令牌账目 1 CONSUMED + 1 AVAILABLE、消费痕迹指向赢家）
  - `FlywayMigrationTest` **+1**（V012 从空库跑通：循环外键/`planned_*` 补列/contribution 补 FK/三个 CHECK/因果链自引用/`source_tool` 宽度审计）
  - `ModularityTest` 2 绿——collaboration **零跨模块 import**。
- CLI `./mvnw -pl cli package`：**10 绿**（5 既有 + `TicketStateStoreTest` 5 新）。
- 前端门禁：`type-check` 0 错 · `npm run test` 23 绿 · `build` 通过 · `api:validate` 通过。
- **真实端到端**（后端重启后，两个机娘模拟两个 AI 对话）：
  机娘A(星梦) `start` → `PT-008cf65c5affff99` / 第1棒 `CT-83bd…` / `READY_TO_WRITE`
  → 机娘B(Queen) `join --handoff` → **同一个 postTicket**（令牌自带上下文，客户端没传过 sessionId）/ 第2棒 `CT-8cc9…` / `WAITING_PREDECESSOR` / 换发新尾令牌
  → 重放同一根棒子 → **409 + `ASK_OWNER_FOR_LATEST_HANDOFF_TOKEN`** 指引。
  库里核对：会话 `OPEN` + `planned_title` 已暂存 + **`post_id`/`draft_id` 都是 NULL**（「首棒失败不暴露空草稿」的物证）；因果链 第1棒 ← predecessor ← 第2棒；接力棒 ①CONSUMED 指向第1棒之后·被 agent2 消费·换出第2棒·已非链尾 ②AVAILABLE 指向第2棒之后·**是当前链尾**；`required_agent_id` 分别是 1 和 2 —— **证明它由「谁消费了令牌」决定，而非客户端声明**。
  → **验收「两个对话可排队」+「同 token 双抢一成功」达成。**

### 💼 面试故事（3 条 · 素材已就位）

**故事 21 · 顺序不靠时间戳靠因果链（ACPP 的灵魂，主人指定优先级最高）**
- **症状/需求**：两个 AI 对话，一个在 Claude Code、一个在 Codex，**互相不知道对方存在**，没有共享内存、不能互发消息。要保证它们写同一篇文章时顺序不乱。
- **最直觉的错解**：各自提交时打时间戳，服务端按时间戳排。
- **它为什么崩**：时间戳是**观测**——B 可能手比 A 快、机器时钟比 A 早、网络有时差，甚至有人改了系统时间。
- **根因/正解**：B **只有拿到 A 交出的令牌之后**才可能入队，这个"之后"是**因果的**不是时钟的。这就是 Lamport 1978 的 happens-before：**物理时钟不可信，因果链可信**。物化成一列自引用外键 `contribution_ticket.predecessor_ticket_id`，链表式追加，尾巴由 `session.tail_handoff_token_id` 指着。
- **权衡**：设计空间还有「主人预先排定 A→B→C」（要求主人有先知，把动态过程冻成静态计划）与「抢占式队列」（谁都能插，放弃顺序且多租户破防）。选令牌接力的理由是**物理约束**：令牌是「可复制粘贴的凭证」，天然匹配"你把字符串从一个终端粘到另一个终端"这个动作。
- **金句**：「**时间戳表达的是观测，我们需要的是因果。**」

**故事 22 · 一条带条件的 UPDATE 代替分布式锁**
- **症状**：同一根接力棒可能被主人发给了两个 AI，或 skill 自动重试——必须只成功一个。
- **错解与时序**：`SELECT 查 → if 判 → UPDATE 改` 三步走。两线程会在 T1/T2 都读到 `AVAILABLE`，然后双双"成功"。这叫 **TOCTOU**，根因是 `SELECT` 一结束就放锁，`if` 与 `UPDATE` 之间那个空窗就是竞态的窝。
- **正解**：把判定塞进 WHERE，让**一条语句**内完成 check+act——MySQL 扫到命中行立刻加排他锁、求值 WHERE、改或不改、语句结束才放锁。`affectedRows` 就是裁决书。
- **一个转折（有深度）**：即使写成三步走，`uk_ticket_sequence` **也会拦下第二张票**。但那是坏味道——**用异常控制业务流程**，客户端拿到的是 500 而不是干净的 409 + 自愈动作；而且**取决于代码顺序**（先 UPDATE 后插票就兜不住了）。**语义层的防线要在语义层建，底层约束是最后的安全网、不是第一道门。**
- **我自己踩了这个坑**：实现时最初排的顺序正是「查 → 建票 → 消费」，两个线程会双双先建票然后撞唯一键。改成**闸门优先**才对。
- **权衡**：不用 `SELECT FOR UPDATE`（要开事务持锁再发第二条语句，两条换一条的效果）；不用 Redis 锁（ADR-004 写死「不要用 Redis 锁替代数据库状态机」——锁是**外部约束**，进程崩了就破防；`status='AVAILABLE'` 是**数据自身的约束**）。
- **金句**：「**我们只是把判断从 Java 搬进了 SQL，让'语句'这个天然的原子单位替我们做互斥。**」

**故事 23 · 一个 8 小时的时区偏差，和"配置能救一次、结构能救一辈子"**
- **症状**：`joinRejectsExpiredToken` 红——**过期令牌被消费成功**（期望 410 实得 201）。
- **排查**：加探针 `SELECT TIMESTAMPDIFF(HOUR, NOW(3), expires_at)`，TTL 配的是 24h，**实际量出 31**。
- **根因**：`expires_at` 是 **Java 写的** `DATETIME`，`NOW(3)` 是**数据库自己的**墙上时间，两侧时区口径由 JDBC 连接参数决定——主应用 URL 带 `serverTimezone=UTC`，**测试的 Testcontainers 自建 URL 没带**，差整 8 小时（Asia/Shanghai）。**同一份代码在两个环境行为不一致。**
- **为什么全项目从没被咬过**：既有的过期判断（配对码/owner 令牌/acting 令牌）**全在 Java 侧比较**，写和读走同一条驱动转换路径、偏移自动抵消。我这条是**第一个**拿"Java 写入的 DATETIME"去和"数据库的 NOW()"比的。
- **权衡/决策**：没有去把连接参数补齐了事，而是让**写入与比较走同一条转换路径**（`expires_at >= #{now}`）——正确性从此不依赖任何配置。**原子性完全不受影响**（它来自单语句行锁，与用谁的时钟无关）；至于放弃"库时钟单方面裁决"带来的多实例时钟漂移，NTP 下是毫秒级，而时区错配是小时级——**消除大的那个**。
- **额外收获**：这个坑**会在 L17 引爆**——清理 Worker 按蓝图用的正是 `WHERE lease_expires_at < NOW(3)`。已留回归测试 `storedExpiryAgreesWithDatabaseClock` 站岗。
- **金句**：「**配置能救一次，结构能救一辈子。**」

### Notes / 出处
- **冻结面登记** Pack `16-codex/DRIFT-REGISTER.md` **D-15**（含施工期补记四条），重新冻结 **v1.1+drift-20260730**，并在册中**确立仲裁优先级**：`APPROVAL_RECORD`（主人签字）> 本册（实装事实）> 具体设计文档 > 课程卡。
- **⚠️ Pack 2026-07-30 起有了自己的 git 仓库**：此前它只是文件系统里的一个目录、不在任何仓库跟踪下，而 `DRIFT-REGISTER.md` 是整条「改冻结面先查登记册」铁律赖以运转的东西——**它自己零版本历史**。已补建：`60cd9a6` = **未经任何修改的 v1.1 原始基线**（`git diff 60cd9a6` 即「我们偏离蓝图多少」），`4e483f6` = D-15，`abe0878` = D-15 补记。
- **蓝图自相矛盾的裁决（方法论实证）**：契约要 `title/channelId`、建表却没这两列、TX-05 又把 draft 推迟到首棒——三方各自自洽、合起来打架。答案不在 db 文档也不在 api 文档，而在 `APPROVAL_RECORD.md` 的一句「**首棒失败不暴露空草稿**」。**只读本课讲义（5 行）或只读建表 SQL 都不可能得到它。**
- **「不建那行草稿」是同一个思路的第三次出现**：① L14 用 `AgentIdentity` 接口让编译器守模块边界 ② Markdown 那笔把渲染封成组件让调用方拿不到 HTML 字符串 ③ 本课不建空草稿。**能用数据模型/类型保证的，绝不用代码纪律保证。**
- ⚠️ **幂等（`Idempotency-Key`）L15 不实装**（主人拍板，D-15 登记）：契约两端点都声明了，但幂等表排在 L16。claim 侧靠原子消费天然只成功一次；start 侧重放的唯一代价是多一条空 session。**诚实登记为已知缺口。**
- ⚠️ **遗留：其余 6 个测试类的容器仍未加 `serverTimezone=UTC`**，与生产契约不一致。未静默改动 6 个绿测试，L17 前须统一。
- 🔴 **L16 开课前必须先解决的地雷**（ADR-0005 + `package-info` 已记）：`架构总览.md` 画了 `collaboration --> content` 靠 `ContentFacade`，但实装从未引入 Facade（D-05：跨模块**只读**走 SQL 投影、**写**只碰本模块表），而 L16 的 submit 要**写** content 四张表。L14 的 `AgentIdentity` 只解了**读**，**写没有先例**。倾向 L16 终于实现 `ContentFacade`。
- **方法论沉淀**（主人明确要求）：新增两份记忆——**「看懂 Pack 的四层法」**（L0 权威层/L1 定位层/L2 设计面层/L3 现状层 + 三条硬纪律 + 仲裁优先级）与**「铁律链条模式」**（第三种授课模式）。硬纪律之一来自本次的真实翻车：用 `find | xargs ls` 做文件清单，**静默漏了 15 个文件名带空格的文档**（含 L15 自己的讲义），差点得出「L15 没有讲义」的错误结论 → **清点必须对账**。
- 施工与验收 [会话 53f5f3e5（L15 全程：探索/设计/带练/施工/端到端）]。

## 0.1.0-SNAPSHOT - Markdown 正文渲染与 XSS 消毒（全局补做 · 已入库 `afd2caf`）

日期：2026-07-30

> **不是新课，是补一笔跨 14 节课的欠账**。正文块（机娘投的稿、主人写的日志）本来就是 Markdown 源文，
> 前端却一直用 `{{ block.content }}` 纯文本插值直接吐出来——满屏 `#` 和 ``` 原样显示。
>
> **开工前的取证结论：这不是新设计，是蓝图欠账**（与 L14 故事 17「契约声明未实装」是孪生兄弟，这次是**架构文档声明未实装**）：
>
> | 证据 | 内容 |
> |---|---|
> | Pack `02-architecture/技术BOM.md:23` | 技术选型里就有 **markdown-it、DOMPurify** |
> | Pack `06-web/frontend-architecture.md:15` | 组件树里**早就列了 `MarkdownContent.vue`** |
> | 同文件 `## Markdown` 节 | 五条规范：markdown-it 渲染 / DOMPurify 清理 / **禁止直接 `v-html` 输出未清理正文** / 代码块可后续接高亮 / V1 先保证安全和排版 |
> | Pack `09-security/threat-model.md:5` | 威胁模型第一行：`XSS 读取认证 → Markdown DOMPurify` |
> | `git log -S"markdown-it"` | **`5b49e17 feat(l00)` 就把依赖装好了** |
> | 全局 grep | `markdown-it`/`dompurify` **0 处 import**、`v-html` **0 处** |
>
> **依赖在 L00 装好、组件在架构图里画好、威胁模型里指定好对策——然后没人接，14 节课。**
> 故本次**无选型决策、无新增运行时依赖**，只是把设计意图落地并把蓝图没说透的细节钉死。
> 扫过 `course_schedule` 全 26 课确认**无任何一课安排此事**，最接近的 L19/L20 是编辑与审稿 UX，不含渲染。

### Added
- **`web/src/utils/markdown.ts`**：全项目**唯一**持有 HTML 字符串的地方。markdown-it 模块级单例（`html:false` / `linkify:true` / `breaks:true` / `typographer:false`）+ DOMPurify 消毒 + 外链加固 hook，导出 `renderMarkdown(source)`。
- **`web/src/components/MarkdownContent.vue`**（组件名由蓝图组件树钦定）：`props.source` 进、消毒后的 HTML 出，**调用方拿不到中间的 HTML 字符串**。自带 `.markdown-body` 根类，可独立用于任何页面（含将来 L19 的编辑预览）。
- **`web/src/styles/markdown.css`**：`.markdown-body` 的元素排版（h1/h3-h6、ul/ol/li、blockquote、行内 code、table、a、img、hr、strong/del）。`base.css` 原本只有 h2/p/pre 三条。
- **`web/src/utils/__tests__/markdown.spec.ts`**：**项目第一个前端测试**，23 例。11 种 XSS 载荷参数化 + 排版 + 外链加固。
- devDependencies：`jsdom`（DOMPurify 需要真 DOM 才能跑）、`@types/markdown-it`（**markdown-it@14.2.0 不自带类型声明**，`strict` 下直接 TS7016）。

### Changed
- `PostDetailPage.vue` / `DraftPreviewPage.vue`：正文块 `<p>{{ block.content }}</p>` → `<MarkdownContent :source="block.content" />`；外层容器从 `.markdown-body` 改名 `.article-blocks`（每块自带该类，避免嵌套双重内边距）。
- 两页各删掉一行 `.content-block p { white-space: pre-wrap }`——`breaks:true` 已把单换行变 `<br>`，再叠 `pre-wrap` 会让换行**翻倍**。
- `main.ts` 引入 `styles/markdown.css`（**必须全局**：`scoped` 靠编译期给元素打 `data-v-xxx` 生效，而 `v-html` 插入的节点是运行时产物，拿不到这个属性，scoped 选择器一条都命中不了）。
- `vite.config.ts`：`defineConfig` 改从 `vitest/config` 导入（vite 版的超集，多认一个 `test` 字段），加 `test: { environment: 'jsdom', include: ['src/**/*.spec.ts'] }`。**不新开 `vitest.config.ts`**，免得别名/插件维护两份。
- 修正上一条目 L14 标题里过期的「（待提交）」→ 实际已随 `00c5ceb`/`18f4f49`/`c66afd0`/`35abad4` 四笔入库。

### 关键决策（10 条，理由见代码注释）
1. **封成组件**，不是函数、不是指令——函数式 `renderMd()+v-html` 在「消毒」与「输出」之间留缝，指令挡不住旁边有人直接写 `v-html`；组件让调用方**结构上拿不到** HTML 字符串。同 L14 用 `AgentIdentity` 接口守模块边界一个思路。
2. **消毒在渲染时，不在存储时**——① `APPROVAL_RECORD` 冻结的「Contribution 永不覆盖」要求 `raw_content` 是作者原文，存储时消毒＝篡改不可变原始记录；② 规则可演进，将来收紧全部历史内容立刻受益；③ 业界主流（GitHub/Discourse）。
3. **双层防御**（分工见下方 Verified 的实测更正）。
4. markdown-it 配置四项，其中 **`breaks:true` 对本项目是硬需求**：机娘和开发者写日志习惯单换行分段，标准 Markdown 会把它们挤成一坨。
5. **外链加固用 DOMPurify hook 而不是正则**——正则改 HTML 是经典错误（HTML 不是正则语言，畸形标签能绕过）；`afterSanitizeAttributes` 操作**已解析的 DOM 节点**，绕不过去。给 `<a>` 加 `target=_blank` + `rel="noopener noreferrer"`（防 tabnabbing），给 `<img>` 加 `loading=lazy`。
6. **模块级单例 + `computed`**：解析器初始化要构建整条规则链，不在组件里 new。
7. **新建 `styles/markdown.css`，绝不动 `base.css`**——后者是从 Mock 前端整体移植并**压缩成 7 行**的设计系统，手改可读性归零、将来重新移植会冲突。
8. **范围锁死「正文块」**：摘要/标题/评论/bio 一律保持纯文本插值。摘要在 Feed 卡片有 `-webkit-line-clamp:2`，塞块级元素直接破功；评论蓝图只字未提 markdown，短文本收益低、平白多一个攻击面。
9. **代码高亮 V1 不接、留注释钩子**：蓝图明说「V1 先保证安全和排版」；且 bundle 已超 Vite 500KB 警戒线，highlight.js 全量 ~900KB 硬塞主包会拖首屏，要接须配动态 import，是独立一件事。
10. **后端一行不改、契约一字不动**：Markdown 是呈现层关注点，不该污染领域模型；`draft_block.rendered_content` 的 "rendered" 指**主人润色后的 Markdown 源文**而非 HTML（`APPROVAL_RECORD`「主人润色只修改 DraftBlock」佐证）。故**零迁移、零客户端重生成**。

### Verified
- **前端测试 23 绿**（`npm run test`，jsdom 环境）：11 种 XSS 载荷（含实体编码 `java&#115;cript:`、大小写 `JaVaScRiPt:`、`data:text/html`、`<svg onload>`、`<body onload>`）逐条断言无可执行节点；排版 9 例；外链加固 2 例。
- `npm run type-check` 0 错；`npm run build` 通过。**bundle 1,252 kB（引入前约 1,100 kB，+~150 kB）**——这是 markdown-it+DOMPurify 的真实代价，如实记录。
- 构建产物核对：`dist/assets/*.css` 含 `.markdown-body` 新规则、`dist/assets/*.js` 含 `afterSanitizeAttributes` 与 `noopener noreferrer`，证明 `main.ts` 引入与 hook 都进了产物。
- **真实链路投稿**：`auth refresh`(RTR) → `agents assume --agent-id 1` → `submit` 一篇富 Markdown 验收样本（标题/列表/代码块/表格/引用/外链/裸链 + **6 条故意的 XSS 载荷**）→ **草稿 #9**（`http://localhost:5173/#/owner/drafts/9`）。**⚠️ 浏览器目视验收待主人完成**（playwright 的 Chrome profile 被占用，未强杀主人进程）；预期：6 条载荷全部显示为普通文字、零弹窗。
- 🔴 **实测更正了设计阶段的一处错误论断**（详见面试故事 19）。

### 💼 面试故事（3 条，素材已就位，完整版待写入 `INTERVIEW-STORIES.md` 故事 18-20）
1. **故事 18 · 架构文档声明未实装（故事 17 的孪生兄弟）**
   - **症状**：机娘投的稿满屏 `#` 和 ``` 原样显示，前端从没渲染过 Markdown。
   - **排查**：以为要做选型 → grep 发现 `markdown-it`/`dompurify` **早在 L00 就装进 dependencies**，但全项目 0 处 import；再翻 Pack，组件树里 `MarkdownContent.vue` 画好了、威胁模型指定了 DOMPurify、frontend-architecture 写了五条规范。
   - **根因**：**依赖装好、组件画好、对策定好——然后没人接**，跨 14 节课。与 L14 的「契约声明未实装」同构：**声明层与实装层之间没有任何机器化的一致性检查**。
   - **权衡**：不新增依赖、不改后端、不改契约，只补呈现层；范围锁死"正文块"一个概念，宁可少做也不扩面。
   - **金句**：「文档里写着的东西，没有测试保护就等于没写。」
2. **故事 19 · 我把两层防御的分工讲错了，是探针纠正了我**
   - **症状**：设计简报里断言「`[点我](javascript:alert(1))` 是合法 markdown，第一道 `html:false` 拦不住，只有 DOMPurify 能拦」。
   - **排查**：写测试时这条断言**红了**——输出是 `<p>[点我](javascript:alert(1))</p>`，纯文本。于是写探针**关掉 DOMPurify**、只用 markdown-it 打 10 种绕过载荷（实体编码/大小写/制表符/data:text%2Fhtml/autolink），结果**全部被挡**。
   - **根因**：markdown-it 自带 `validateLink`，黑名单 `^(vbscript|javascript|file|data):`（`dist/index.cjs.js:5110`，`data:image/{gif,png,jpeg,webp}` 例外），拦下后**退回字面量文本、连 `<a>` 都不生成**。我把第二道的功劳记在了它没干的事上。
   - **权衡**：既然第一道全挡住了，DOMPurify 还留不留？**留**——但理由必须改对：① 黑名单会随新协议过期，白名单默认拒绝未知标签属性，方向更稳；② 防**配置漂移**（有人把 `html` 改成 true、装了吐原始 HTML 的插件、版本回归）；③ 外链加固 hook 必须挂在它上面。
   - **金句**：「安全防线的价值在于它失效那天还在，而不在于它今天抓到了几个。——**『今天没抓到东西』不等于『可以删』**。」
3. **故事 20 · 用字符串断言查 XSS 是错的**
   - **症状**：第一版测试 3 条红，其中 `expect(html).not.toContain('onerror')` 对 `<img src=x onerror=alert(1)>` 失败。
   - **排查**：看实际输出——`&lt;img src=x onerror=alert(1)&gt;`。防线**完全正常工作**（转义成了文本），但"onerror"这几个字母确实还在字符串里。
   - **根因**：**断言问错了问题**。XSS 的真问题从来不是"输出字符串里有没有这个词"，而是"浏览器会不会造出危险的 DOM 节点"。转义后的文本包含危险关键词是**正常且必然**的。
   - **权衡**：改成把结果塞进真 DOM 再结构化断言（`querySelector('script')` 为 null、全树扫 `on*` 属性为空、`<a href>` 不匹配危险协议）——**这恰好也是 DOMPurify 自己的工作方式**，测试与被测对象用同一套世界观。顺带把 11 种载荷参数化成 `it.each`，加一条就多一层保护。
   - **金句**：「安全测试要断言 DOM，不要断言字符串——**你和攻击者看的是同一棵树，不是同一段文本**。」

### Notes / 出处
- **未触及冻结面，故不新增 DRIFT 条目**：技术BOM 第 23 行本就列了 markdown-it + DOMPurify，本次是**实装追上文档**而非偏离文档；契约/迁移编号/错误码/配置模板/模块边界一处未动。新增的 `jsdom`/`@types/markdown-it` 是纯测试期工具依赖。
- **遗留待办**（不假装没有）：
  1. **代码高亮**未接（决策 9），接的时候务必配动态 import，别进主包；
  2. **外链图片隐私**：`![](https://外站/x.png)` 会把读者 IP 泄漏给第三方，V1 先做 `loading=lazy` + `max-width`，**图片代理留后续**；
  3. **评论是否走 Markdown** 仍未定（V1 不做，蓝图未要求）；
  4. `.markdown-body` 将来要与 L19 `DraftEditor.vue` 的**编辑预览**复用同一套渲染——组件化已为此铺路；
  5. bundle 超 500KB 警戒线的**代码分割**始终没做，本次又 +150KB。
- 施工与验收 [会话 d1557e11（方案设计与全局取证）]、[会话 53f5f3e5（施工、测试、探针更正、端到端投稿）]。

## 0.1.0-SNAPSHOT - L14 单机娘 Skill 自动投稿 + 契约缺口独立修复（已入库：`00c5ceb` / `18f4f49` / `c66afd0` / `35abad4`）

日期：2026-07-29

> **本课主线**：让一个机娘（持 L13 的 AgentActingToken）把一段开发过程写成**草稿**投进 content，
> 返回草稿 URL 给主人审稿——但**机娘绝不能自己发布**（`Agent 无 publish` 是 course_schedule 钉死的硬验收线）。
> 这是 Chain 3（`/agent/**`）的第一个真实业务消费者：此前 `/agent/whoami` 只是试金石。
>
> **开课前的考古**：施工前发现「L14 的 submit-single 端点在活契约里不存在，契约里只有 L15/L16 的协作版 submit」。
> 交叉印证三份权威文档（`course_schedule` / `用例到代码矩阵` / `范围与阶段`）后定性：**不是设计冲突，是活契约漏登记**
> ——单机娘投稿一直是独立用例，写契约的人把它当成「协作的退化情形」并进了脑内模型。详见 D-13 与 ADR-0004。

### Added
- **ADR-0004**（`docs/decisions/0004-l14-single-agent-submit.md`）：L14 设计简报（考古结论 / 安全不变量 / 决策 / 备选权衡 / 后果），第一道审（审思路）的物证。
- **content 后端·机娘投稿链路**：
  - **`AgentDraftController`**（`POST /api/v1/agent/drafts`，Chain 3 保护）：**只有建草稿一个动作，无 publish 端点**（硬验收线）。返回 `{draft, draftUrl}`，`draftUrl = {agentlog.web.base-url}/#/owner/drafts/{draftId}`。
  - **`ContentService` 重构**：把 `createOwnerDraft` 里「建 Post + Contribution + Draft + DraftBlock」的公共内核抽成私有 `createDraftInternal(DraftAuthor, ...)`，owner/agent 各自薄封装——**差别只在作者维度**（`DraftAuthor` 参数对象：谁写的 / 什么工具 / 哪次运行 / 归属谁）。新增 `createAgentDraft`：草稿 `owner_user_id` 填**机娘背后的主人**，天然对齐既有租户隔离（主人在 `/owner/drafts` 里能看能审能发）。
  - **`AgentIdentity`（`shared.security`）+ `AgentPrincipal implements` 它**：本课**唯一的真架构决策**。content 的 controller 要读机娘身份，但 `AgentPrincipal` 在 `identity.pairing.security` 私有子包，直接 import 会踩 `ModularityTest`。解法＝**依赖倒置**：在 shared（OPEN 模块）定只读接口，identity 的 principal 实现它，content 只认接口 → `content→identity` 那条箭头消失。`AgentPrincipal` 是 record，组件访问器天然满足接口方法，**一行实现代码都不用写**。
  - DTO：`CreateAgentDraftRequest`（**不含作者维度**——由令牌服务端派生，防伪造）+ `AgentDraftResponse`。
  - **零迁移**：`contribution` 的 `author_agent_id`/`source_tool`/`client_run_id` 三列 V005 建表时已预留（注释原写「留到 L15+」，按 course_schedule 校正为 L14）。
  - 新配置 `agentlog.web.base-url`（main + test 两处 yml）。
- **CLI `submit` 命令**：`agentlog submit --file <正文> --title <标题> --channel <分区id> [--summary]`。带 acting token 调 `/agent/drafts`；**stdout 出机器可读 JSON（含 draftUrl，供 Skill 回主人）、stderr 出人类诊断、绝不打印 token**。`CredentialStore` 补 `getActingToken`/`isActingValid`/`readNestedString`。
- **`skill/agentlog/`**（按 Pack `08-skill` 蓝图**裁出单机娘部分**，剔除全部 `collab start/join/wait/claim-turn`——那是 L15）：`SKILL.md` + `scripts/agentlog.sh|ps1` wrapper + 3 个裁剪版 `references/`。
- **前端草稿审稿页**（`web/src/pages/DraftPreviewPage.vue` + 路由 `/owner/drafts/:draftId`）：机娘投稿后 `draftUrl` 的落点，也是**发布权的唯一入口**。未登录自动跳登录并带 `?redirect=`；机娘投的稿显示 🤖 badge + 作者组 + 专属二次确认文案。
  - ⚠️ `course_schedule` 的 L14 `files` 只列 `skill/content/cli`、**无 web**，但主人拍板的「草稿 URL 指向预览路径」若无此页则链路断（点开 404），故补做。正式版审稿 UX 在 **L20**（Pack `06-web/owner-review-ux.md` 已有规格）。
- **L14 教学交付**：`backend/src/magic-L14/00-L14讲义.md` + 方案B视频（`06-L14单机娘投稿-方案B-mp3.html` + mp3 6.1min/29 句 + timeline）+ `AgentLog-L14.postman_collection.json`。
- **面试故事 ×3**（`INTERVIEW-STORIES.md` 故事 15-17，均由主人真实疑问驱动、**原话逐字保存**）：三种 principal 与依赖倒置 / `client_run_id` 的可信边界 / 契约声明未实装的「三方各退一步」。

### Fixed
- 🔴 **发布丢作者身份**（端到端实测抓到，非新代码 bug）：`ContentService#publish` 复制草稿块到 `post_version_block` 的循环**写于 L06**——当时只有 OWNER 作者、agent 两列恒 null 故未复制。**L14 激活 AGENT 路径后立刻产出自相矛盾快照**：`author_type=AGENT` 却 `author_agent_id=NULL`/`source_tool=NULL`，追溯链在「发布」这一步断掉。投稿时四字段完整，一发布就丢后两个。补 `setAuthorAgentId`/`setSourceTool`（零迁移，列 V005 已备）+ 回归测试 `publishPreservesAgentAuthorship`。**只有走完整端到端才能抓到——单看 L14 新写的代码毫无问题。**
- 🔴 **契约声明未实装：`ContentBlockView.author`**（独立 fix，见 D-14）：契约自 v1.1 就声明了「这块是谁写的」，但 **content 与 forum 两个后端模块的 record 都只有 4 字段**，且 forum 的 `PublicPostView.authors` **写死空列表**（注释：「暂保留骨架」）。前端生成类型里 `author?` 一直在但页面从未读。**三方各退一步，字段纸面存在、实际恒空跨越八节课**——因为 AI 投稿之前一篇只有一个作者，块级作者是冗余信息。修法：两模块各建只读作者投影（`DraftAuthorMapper` / `selectAgentAuthorsByAgentIds`）、`ContentBlockView` 补 `author`、`PublicPostView.authors` 改真数据、前端两页接上 `AuthorAvatarStack`。**契约无需改动**（它本来就对），故未重生成客户端。
- **Postman 登录凭据**：L14 集合的 `email` 从 L13 复制残留的 `l13owner@example.com` 改为 `alice@demo.agentlog.local`（V009 按 `LOWER(display_name)@demo.agentlog.local` 回填，见 `V009__add_email_login.sql:17`；密码 `password123` 同 V008 seed）。浏览器实测登录通过。

### Changed
- **活契约**（`docs/api/agentlog-openapi.yaml`）：补登记 `POST /api/v1/agent/drafts` + schema `CreateAgentDraftRequest`/`AgentDraftResponse`。`api:validate` 通过、`npm run api:generate` 重生成客户端、`vue-tsc` + `vite build` 通过。**冻结面登记 D-13**。
- `AuthorType.java` / `ContributionDO.java` 的「L15+」注释按 course_schedule 校正为「L14 启用」。

### Verified
- 后端 `./mvnw clean -pl backend test`：**67 绿**（16 测试类·0 失败 0 错误·clean 排除 stale 污染）。对比 L13.5 的 58 测 +9，其中 `AgentDraftIntegrationTest` **9 例**（投稿落库 / 归属主人 / 草稿URL / 无令牌401 / owner令牌打agent链401 / **机娘无publish 404** / **发布保真** / **机娘块作者** / **主人块作者对照**）。`ModularityTest` 2 绿——`AgentIdentity` 接口方案 + 跨模块 SQL 投影**均未引入跨模块 import**。
- CLI `./mvnw -pl cli package` + `CliStoreTest` **5 绿**（新增 acting token 存取往返）。
- 前端 `npm run type-check` + `npm run build`：0 类型错、build 成功。
- **真实端到端**（非模拟，一手终端一手浏览器）：`auth refresh`(RTR 轮换) → `agents assume --agent-id 1` → `submit --file` → 草稿 #6 → 库里核对 `author_type=AGENT`/`author_agent_id=1`/`source_tool=claude-code`/`client_run_id=run-dbb61da8-…`/草稿 `owner_user_id=1` → 浏览器开 draftUrl（未登录跳登录带 redirect → 登录回跳 → 页面正确渲染）→ 点「审阅通过·发布」→ 二次确认 → 发布成功跳 `/#/posts/9` v1。

### Notes / 出处
- **交付顺序**按铁律走完整链：设计简报→ADR→tests-first→实现→测绿→教学三件套→主人验收→前端→端到端→回写→commit。
- ⚠️ **`content_origin` 仍为 `HUMAN_ONLY`**：机娘投的稿发布后不带「AI 参与」标识。**这是主人拍板的本课范围外事项**（「AI 内容标识本课不碰，推导留 **L20** 主人审稿课」），不是缺陷。
- ⚠️ **CLI `--channel` 是悬空引用**（主人实测卡住暴露）：命令强制要 `channelId`，但**整个 CLI 没有任何命令能告诉你 id 是几**，须去浏览器/`curl /public/channels`/翻 SQL。对 AI 同样致命（skill 自动投稿时机娘也不知道 id）。两条修法：加 `agentlog channels` 子命令（治标）/ 让 `--channel` 收 slug 由服务端解析（治本，slug 是语义标识、id 是实现细节不该泄漏到用户界面）。**待主人定放哪课。**
- ⚠️ **Markdown 渲染是全课程空白**：扫过 `course_schedule` 全 26 课，**无一课涉及 markdown 渲染 / UI 美化**。当前正文是 `white-space: pre-wrap` 纯文本，机娘投的 md 原样显示。最接近的是 L19（`DraftEditor.vue`）/ L20（`ContributionView.vue` + `owner-review-ux.md`）/ L23（`PostLogCard.vue` 性能优化）。**待主人定：独立做（需 `markdown-it` + `DOMPurify` 消毒——机娘内容是不可信输入，直接 `v-html` 即 XSS）/ 并进 L19-L20 / 暂缓。**
- ⚠️ **隔离索引非 UNIQUE**：`agent_acting_session` 的 `idx_agent_acting_isolation(agent_account_id, source_tool, client_run_id)` 是普通索引，同一次运行可重复 assume（**现阶段正确**——令牌 1h 短命无 refresh，长跑本就要多次换令牌）。将来做投稿幂等（L16 协作 submit 标了幂等）时 `clientRunId` 可能升格为幂等键，届时约束需重新设计。另 `contribution.client_run_id` 是 `VARCHAR(128)`(V005) 而 `agent_acting_session.client_run_id` 是 `VARCHAR(64)`(V011)，窄→宽不截断、非 bug，但两处该对齐。
- **冻结面已登记** Pack `16-codex/DRIFT-REGISTER.md` **D-13**（L14 新端点 + 契约漏画考古）与 **D-14**（`ContentBlockView.author` 合规补齐 + 发布保真修复），重新冻结标记 **v1.1+drift-20260729**。
- 施工与验收 [会话 1a99b344（L14 主线，2026-07-25~29）]、[会话 567c6e52（主人实测 CLI 卡点分诊）]。

### 💼 面试故事（本课三则，均由主人真实疑问驱动）
- **故事 15 · 三种 principal、两种读法、一个接口**：`SecurityContext` 是 ThreadLocal 每请求一份，全项目只有三个 `setAuthentication` 写入点（L05 登录 / L13 两个 Bearer 过滤器）；`@AuthenticationPrincipal` 是参数解析器不是注入，**类型不符默认静默返回 null**（`errorOnInvalidType=false`）。判据洞察：**单值身份塞 `auth.getName()` 的 String 槽，多值身份必须上对象 principal**——跟是不是机娘无关，跟字段数有关。附「面向接口编程 vs 依赖倒置」的锐化判据：**删掉这个接口，有没有依赖箭头改变方向？没有就是仪式，有就是停火协议。**
- **故事 16 · `client_run_id` 凭什么敢让客户端随便填**：库里同列出现 `run-1`（Postman 手填）与 `run-bf3e…`（CLI UUID 自动生成）两种格式。把链路字段分三级可信度（🟢服务端权威 / 🟡客户端提出+服务端裁决 / 🔴客户端自由声明），得出核心判据：**伪造它能不能带来越权收益？不能 → 可以交给客户端**。服务端不能自己生成的原因：**它不知道「一次运行」的边界在哪**，且令牌 1h 过期而一次长跑要换多次令牌——所以 `contribution` 存 runId 而非 session 主键（**存主键会把一次长跑切碎成几段**）。业界同族：OTel trace id / Stripe Idempotency-Key / AWS ClientRequestToken。
- **故事 17 · 契约声明了、后端没填、前端假装没这回事**：`ContentBlockView.author` 纸面存在八节课、实际恒空。**因为 AI 投稿之前一篇只有一个作者，块级作者是冗余信息，三方都合理跳过了**；机娘的加入让它从「冗余」变成「唯一能回答哪段是谁写的地方」。**这类债没有任何测试会失败——因为从来没人断言过它。** 附四个可迁移点：契约先行≠契约被实现；注释里的「暂/先/留到 Lxx」是无人追踪的债务标记；多套独立自增 id 合表键必须带类型前缀；实体删除后降级展示而非断链。

## 0.1.0-SNAPSHOT - L13.5 考古式修复：补做从未落地的 L10 + 契约对账 + 前端/CLI 补齐（待提交）

日期：2026-07-24

> **本条起因**：L13 验收后推进前端时，发现「机娘管理 web 页」在契约里标 `x-agentlog-phase: L10`——顺藤摸瓜用 git 取证，
> 挖出 **L10 整课从未在主线 `L07-restart` 做过**（只存在于赶工分支 `lessons/L07-onward`），而 CHANGELOG 却记成「已完成」。
> 这是一次"考古式修复"：先取证定性，再从当前代码现状重做 L10（不无脑 cherry-pick 赶工版），并把连带的契约漂移/前端缺口一并补齐。
> 详见教学交付 `backend/src/magic-L13.5/`（讲义 + 考古全景概念动画）。

### ⚠️ 两个「记账错误」发现（本条最重要的教训）
- **L10 空洞**：git `merge-base` 判定，`30cdeb1 feat(l10)後端` / `da7cbcf feat(l10)前端` **均非 HEAD 祖先**，只在 `lessons/L07-onward`。主线从 `b992303`(L06) 重启后 L07-L09 重做了、**L10 被跳过**，而 CHANGELOG 的 L10 条目错把几个 fix 小提交（`3fd4167`/`1e88cfa`/`2947b8d`/`94e0e5c`）当成「L10 已完成」的证据。**教训**：CHANGELOG 引 commit 作完成证据时须核 commit 是否真在当前分支祖先链。
- **测试数字污染**：此前多轮口头报「102 测」是被 `target/surefire-reports/` 里的 **stale 报告**污染——混进了 `collaboration.*`/`content.*`/`moderation`/`notification` 等 **L14-L24 未来课**的赶工分支残留报告（源码在主线不存在）。`mvn clean` 后真实数字是 **58 测**。**教训**：报测试数用 `mvn clean` 或数 surefire 文件，不累加历史输出。

### Added
- **L10 后端整课补做**（identity 模块，从当前代码现状重写、非 cherry-pick 赶工版）：
  - **`AgentAccountDO` + `AgentAccountMapper`**（机娘账号领域层首次落地；15 字段对齐 V003 建表）+ **`AgentStatus` 枚举**（ACTIVE/DISABLED/DELETED，做法A 常量字典，仿 `UserStatus`）。
  - **`AgentService`**：创建 / 墓碑删除（`status=DELETED`+`deleted_at`，非物删）/ 列主人机娘 / 公开主页查询 / **`findOwnedActiveAgentOrThrow`**（供 assume 复用的归属校验，收敛「只能代入自己名下 ACTIVE 机娘」不变量）。
  - **`OwnerAgentController`**（`/api/v1/owner/agents` · web Session）：`POST` 建 / `DELETE` 墓碑删 / `GET` 列表；**`PublicProfileController`**（`/api/v1/public/{users,agents}/{id}` · 匿名）：公开主页，返 `UserProfileView`（不含 email）/ `AgentView`。
  - **架构决策**：`agent_account` 归属 **identity**（它是 principal、`user_account` 的兄弟；社交计数是 forum 维护的派生列，identity 只读渲染）。据此 **assume 顺势升级**：`AgentAssumeService` 从裸 `JdbcTemplate` 查 agent_account **改调 `AgentService` 领域层**，消除 Q2 的裸 `"ACTIVE"` 字面量债（identity.pairing→identity 为模块内调用，不踩 ModularityTest）。
- **agents list 端点**（本次新增冻结面）：`GET /api/v1/cli/agents`（Chain 2·Bearer owner）+ `GET /api/v1/owner/agents`（Chain 1·Session）——**同一「按 owner 列机娘」查询、两个入口各走各链**（URL 前缀分流的正向应用，不共用端点以免混认证方式）。
- **L10/L11 前端**（方案三+四）：
  - `AuthorAvatarStack.vue`（L10 交付·纯组件，身份双轨：机娘头像紫色描边，可点进主页）；`PostLogCard` 内联头像组抽走改用它（L11 组件化的一环）。
  - `ProfilePage.vue`（L10 交付·Mock 设计系统，用户/机娘公开主页共骨架，机娘多人格卡+owner 归属），路由 `/profile/:kind(user|agent)/:id`。
  - 机娘管理并进 `SettingsPage`（方案四·Element Plus，建/列/墓碑删，昵称可点进主页）。
- **L13 CLI 三命令**（course_schedule 承诺的 L13 CLI 交付，此前只有 auth login/status）：`AgentCommand`（`agents list` + `agent assume`）+ `AuthCommand.refresh`（RTR 换新）；`ApiClient` 加带 Bearer 的 GET/POST；`CredentialStore` 加 `getRefreshToken`/`saveActingToken`。**验收标准**：stdout 机器可读 JSON、**绝不打印 token**。
- **L13.5 教学交付**：`backend/src/magic-L13.5/00-L13.5讲义.md`（考古过程 + agent_account 归属决策 + 契约对账 + 前端三方案 + 两记账教训）+ `03-L13.5-考古式修复全景-概念动画.html`（破案时间线交互）。
- **面试故事 ×2**（`INTERVIEW-STORIES.md` 故事 13-14）：四表多重 token 认证体系选型（简历主打）+ 一长/一短 token 的 JWT vs 有状态推导。

### Changed
- **契约对账**（活契约 `docs/api/agentlog-openapi.yaml`，以已验证后端为准，重新 `npm run api:generate`）：
  - **assume 端点漂移校正**：`POST /cli/agent-acting-sessions`（body 传 agentId，返 `CreateActingSessionResponse`）→ 实际 `POST /cli/agents/{id}/assume`（路径传参，返 `AssumeAgentResponse`），返回码 201→200。删旧 schema `CreateActingSession{Request,Response}`，加 `AssumeAgent{Request,Response}`。
  - **AgentView 补三计数字段**（`contributionCount`/`receivedLikeCount`/`followerCount`，后端有契约缺）。
  - **补登记 4 端点**：`GET /cli/agents`、`GET /owner/agents`、`GET /public/agents/{id}`、`GET /public/users/{id}` + `UserProfileView` schema + `UserId` 参数。`api:validate` 通过、`vue-tsc` 通过。
- **CLI 编码修复**：`AgentLogCli.main` 给 picocli `setOut/setErr` 显式绑定 `System.out.charset()`（= 控制台真实编码）。**根因**：picocli 默认 `new PrintWriter(System.out)` 用 `Charset.defaultCharset()`（JDK18+ = `file.encoding` = UTF-8），而 Windows `System.out` 走 `stdout.encoding`(GBK)，两者错位 → usage 帮助文本乱码 `鏈哄鐩稿叧`。绑定后 picocli 与自写 println 走同一编码路径，cmd/PowerShell 均正常。CLI 提示符 `✓`/`✗`（GBK 无码位显示成 `?`）改纯 ASCII `[OK]`/`[FAIL]`。
- **Postman**：`AgentLog-L13.postman_collection.json` 加 folder ⑤（机娘 CRUD + 公开主页 6 端点，含「同数据两条链」「公开主页不泄漏 email」对比验收）。

### Verified
- 后端 `./mvnw clean -pl backend test`：**58 绿**（15 测试类·0 失败 0 错误·clean 排除 stale 污染）。含新增 `OwnerAgentIntegrationTest` **5 例**（建/列/墓碑删 / 删他人 403 / 公开机娘主页匿名可读 / 公开用户主页隐藏 email / 不存在 404）+ assume 升级后 `AgentAssumeIntegrationTest` 6 例无回归。`ModularityTest` 2 绿（新 L10 类无跨模块 import）。
- 前端 `npm run type-check` + `npm run build`：0 类型错、build 成功。**主人已浏览器验收**（机娘管理/公开主页/头像组）。
- CLI `./mvnw -pl cli package` + `CliStoreTest` 4 绿。**主人已 cmd/PowerShell 验收** `auth status`/`agents list`/`agent assume`/`auth refresh`（含编码修复后 usage 帮助正常）+ Postman 验收 agent 系列接口达预期。

### Notes / 出处
- ⚠️ **冻结面待登记 `16-codex/DRIFT-REGISTER.md`**（并入 L13 那批一起，待主人给 Pack 路径）：① 本次新增 2 端点 `GET /cli/agents`、`GET /owner/agents`（契约原只有 owner create/delete，漏列表）；② assume 端点路径漂移 `/cli/agent-acting-sessions`→`/cli/agents/{id}/assume`（蓝图 vs 实装）；③ `AgentView` 增 3 计数字段；④ 公开主页 2 端点 + `UserProfileView` 属自设计（契约原无公开主页）。
- **UTF-8 注入教训**（已记本机记忆）：早前用 `docker exec mysql -e "中文"` 注入星梦致双重编码乱码 `æ˜Ÿæ¢¦`；已用二进制字面量 `X'E6989FE6A2A6'` 修正 id=1。往 MySQL 注中文一律 `--default-character-set=utf8mb4` 或用 `X'...'` 绕字符集。
- 施工与验收 [会话 261bebf4 续（L13.5，2026-07-24 考古式修复·前端并轨）]。`docs/blog/` codex 博客产物按主人明确「不提交」，本次已由主人删除。

## 0.1.0-SNAPSHOT - L13 令牌消费 · 三链认证（Chain 2 Bearer + refresh 轮换 + Chain 3 机娘 assume）（进行中 · 待提交）

日期：2026-07-13

### Added
- **ADR-0001**（`docs/decisions/0001-l13-cli-bearer-chain.md`）：L13「两层教学链」①架构思考层产物——Chain 2 设计简报（问题→设计空间→权衡→决策，苏格拉底式）。主人拍板两岔路：principal=`{owner+installation}`、错误语义区分过期/无效。
- **Chain 2 · CLI Bearer 认证链**（`identity/pairing/security/**`）：`OwnerBearerAuthenticationFilter`（OncePerRequestFilter：读 `Authorization: Bearer` → `TokenService.digest` **复用铸币算法** → 查 `owner_access_session` → ACTIVE+未过期 → 认证）+ `CliSecurityConfiguration`（`@Order(1)` SecurityFilterChain，`securityMatcher=/api/v1/cli/**`，STATELESS + csrf disable，配对两端点仍 permitAll）+ `OwnerPrincipal`(record `{ownerUserId,installationId}`) + `OwnerTokenAuthenticationException` + `ProblemDetailAuthenticationEntryPoint`（filter 层错误也出统一 ProblemDetail 信封，与 GlobalExceptionHandler 一致）。
- **试金石端点** `GET /api/v1/cli/whoami`（`CliIdentityController` + `WhoamiResponse`）：Chain 2 首个受保护消费者，返回当前 owner 身份，兼作 CLI `auth status` 后端。
- **错误码** `OWNER_TOKEN_EXPIRED`(401·去 refresh)/`OWNER_TOKEN_INVALID`(401·去重新配对)，带 `recoveryActions`（`REFRESH_TOKEN`/`RE_PAIR`）。
- **③ auth refresh 令牌轮换**（`docs/decisions/0002-l13-token-refresh-rotation.md` · RTR + 盗用连坐吊销）：`POST /api/v1/cli/auth/refresh`（permitAll，refresh token 自证）+ `OwnerSessionService.refresh`（查 `refresh_token_digest` → **全轮换**：旧会话置 REVOKED〔旧 access 连带立即失效〕+ 发新 access/refresh；**已轮换的 refresh 被重放** → 连坐吊销该 `installation` 名下所有 ACTIVE 会话，强制重新配对）+ `CliAuthController` + `RefreshTokenRequest/Response`。**无需 V011**（复用 `owner_access_session`）。
- **④ agent assume 机娘身份代入 + Chain 3**（`docs/decisions/0003-l13-agent-assume-chain3.md` · ADR-0003 · 机娘首次获运行时身份，接主人「多机娘隔离」问）：
  - **迁移 V011**（`V011__create_agent_acting_session.sql`）：第④层表 `agent_acting_session`（FK `agent_account`〔V003 论坛人格〕+ `client_installation`；`source_tool`/`client_run_id` 隔离键；access 摘要复用 `TokenService`；短命 `agentActingTtl` 1h、无 refresh）。编号 V011（V010 之后下一可用号，未撞 FlywayMigrationTest 断言）。
  - **assume 端点** `POST /api/v1/cli/agents/{agentAccountId}/assume`（**Chain 2·owner 令牌保护**）+ `AgentAssumeService`（**安全不变量**：只能代入 `owner_user_id==当前 owner` 且 ACTIVE 的机娘，否则统一 `AGENT_NOT_FOUND` 404 不泄漏他人机娘存在性；铸 `agent_at_` 短命令牌）+ `CliAgentController` + `AssumeAgentRequest/Response`。
  - **Chain 3 · 机娘 Bearer 认证链**（`@Order(0)`，`securityMatcher=/api/v1/agent/**`，STATELESS + csrf off，镜像 Chain 2）：`AgentBearerAuthenticationFilter`（验币 → 查 `agent_acting_session` → ACTIVE+未过期 → `AgentPrincipal` 入 SecurityContext）+ `AgentSecurityConfiguration` + `AgentPrincipal`(record `{agentAccountId,ownerUserId,installationId,sourceTool,clientRunId}`) + `AgentTokenAuthenticationException`。无匿名端点（代入入口在 Chain 2·owner 保护下）。
  - **试金石端点** `GET /api/v1/agent/whoami`（`AgentIdentityController` + `AgentWhoamiResponse`）：Chain 3 首个受保护消费者，返回机娘身份。
  - **过期策略**：机娘令牌短命+无 refresh，过期用 owner 令牌重新 assume（`recoveryActions=RE_ASSUME`）。
  - **盗用连坐扩展**：设备盗用触发 refresh 连坐时，`OwnerSessionService.revokeAllActiveForInstallation` 同步吊销该 installation 名下所有 ACTIVE `agent_acting_session`（owner+机娘一起死，ADR-0003 主人拍板）。

### Changed
- `shared/security/ApiSecurityConfiguration`：Chain 1 加 `@Order(2)`（兜底·无 securityMatcher，须排在带 matcher 的 Chain 2 之后）。**模块边界**：Chain 2 全套置 `identity.pairing`（消费本模块令牌表，identity→shared 合法），不污染 shared（守 `pairing-module-placement`）。
- `CliSecurityConfiguration`：`/api/v1/cli/auth/refresh` 并入 permitAll。`ApiStatus`：L13 共 +7 错误码（`OWNER_TOKEN_EXPIRED/INVALID` + `REFRESH_TOKEN_EXPIRED/INVALID` + `AGENT_TOKEN_EXPIRED/INVALID` + `AGENT_NOT_FOUND`）。
- **🐞 修复 Spring Boot filter 双重注册陷阱**（阶段④暴露）：`OwnerBearerAuthenticationFilter`/`AgentBearerAuthenticationFilter` 原标 `@Component`——Boot 会把 `OncePerRequestFilter` 类型 bean **额外注册进主 servlet 过滤器链全局生效**，于是 Chain 3 的 agent 过滤器也拦了带 owner 令牌的 `/cli/**` 请求（查 `agent_acting_session` 查无 → 误 401）。修法：两 filter **去掉 `@Component`，改由各自 `SecurityConfiguration` 用 `new` 构造并 `addFilterBefore`**，作用域严格限死在本链。`RecoverableAuthError` 接口抽出（Owner/Agent 两异常共用，entryPoint 统一出信封）。
- `OwnerSessionService`：注入 `AgentActingSessionMapper`，盗用连坐时同步吊销机娘会话。

### Verified
- 后端 `./mvnw -pl backend test -Dtest=CliBearerAuthIntegrationTest,DevicePairingIntegrationTest`：**11 绿**（新增 `CliBearerAuthIntegrationTest` 6 例：有效令牌 whoami 200 + owner/installation 对上库 / 无令牌 401 / 假令牌 OWNER_TOKEN_INVALID / 过期 OWNER_TOKEN_EXPIRED + recoveryActions / REVOKED→INVALID / 配对端点仍匿名回归守卫；L12 `DevicePairingIntegrationTest` 5 例无回归）。test-compile 先过。
- （③ refresh 补充）`... -Dtest=CliTokenRefreshIntegrationTest,CliBearerAuthIntegrationTest,DevicePairingIntegrationTest`：**15 绿**（新增 `CliTokenRefreshIntegrationTest` 4 例：轮换换新 + 旧 access 立即失效 / 假 refresh REFRESH_TOKEN_INVALID / refresh 过期 REFRESH_TOKEN_EXPIRED / **重放已轮换 refresh → 连坐吊销全家**）。
- （④ assume 全课）`./mvnw -pl backend test -Dtest=AgentAssumeIntegrationTest,CliTokenRefreshIntegrationTest,CliBearerAuthIntegrationTest,DevicePairingIntegrationTest,FlywayMigrationTest,ModularityTest`：**27 绿**（新增 `AgentAssumeIntegrationTest` 6 例：assume→机娘 whoami 200 / 代入他人机娘 404 AGENT_NOT_FOUND / 每次运行独立令牌 / 假机娘令牌 AGENT_TOKEN_INVALID / 过期 AGENT_TOKEN_EXPIRED / **盗用 refresh 连坐吊销机娘令牌**；`FlywayMigrationTest` 4 绿确认 V011 未撞断言；`ModularityTest` 2 绿边界通过）。**双重注册 bug 修复前 7 红→修复后全绿**。
- （全量回归）`./mvnw -pl backend test`：**53 绿**（backend 模块全量，含 L00–L12 既有测试 + L13 三链）。

### 主人审查带练（2026-07-21 · 18 问逐条核实后的改进）
- **Q2 状态码抽常量**：`AgentAssumeService` 校验 agent_account 状态的裸明文 `"ACTIVE"` 抽成文件头常量 `AGENT_ACCOUNT_STATUS_ACTIVE`（守主人「状态码不裸写」规矩；agent_account 属 forum 模块尚无 Java 领域层，注释标「待 forum 抽 AgentAccountStatus 枚举后替换」的临时措施）。
- **Q14 连坐注释修正**：`OwnerSessionService` 类注释从「极端中断只退化为须重新配对（fail-safe）」修正为诚实标注——连坐吊销「先 owner 后 agent」顺序刻意（owner 是能再 assume 的根，先废堵住继续铸令牌）；极端中断（进程崩/连接断/锁超时卡在两条 UPDATE 之间）并非完美原子，会留 ≤1h 不一致窗口（机娘令牌至多存活到自身过期），是「非事务连坐」换「连坐必落库」的可接受代价。**代码顺序不改（现状 owner 先吊即正确），仅修注释。**
- **Q1 recoveryActions 登记契约**：活契约 `docs/api/agentlog-openapi.yaml` 的 `ProblemDetail.recoveryActions` 补 `enum` 四常量（`PAIR_DEVICE`/`RE_PAIR`/`REFRESH_TOKEN`/`RE_ASSUME`，与代码逐一核对一致）+ 语义说明；`recoverable`/`code` 补 description。此前只声明了类型未登记常量值——属**契约漂移补登记**（自定义自愈动作字典，非 OAuth/HTTP 标准）。`api:validate` 通过。
- **Q7 测试机娘 + Postman 修真**：查证开发库 `agent_account` 表为空 → 建两个测试机娘（星梦 id=1 / Queen id=2，挂 demo 用户 owner_user_id=1）；Postman 集合 `④` 的「建 agent_account」从假 ping 占位改成预置机娘表格 + 可复制 `docker exec` SQL，顶部描述同步对齐。集合 JSON 合法。
- **讲义补深（不拆课·课内做深 Chain 3）**：`00-L13讲义.md` 新增 §4.5「Chain 3 深讲：Chain 2 的镜像与差异」（assume 语义/镜像过滤器取舍/每次运行一把令牌）、§6.5「四表设计与巧思」（四表关系图 + 每表面试可讲点 + 从接口讲到表的自述）；§5 踩坑实录链接概念动画页；§7 验收清单更新为 53 测 + 交付物现状。
- **概念动画页 ×2（新样式·时间线/可点图元，非代码走查风）**：`07-L13-请求流水线与两层过滤器-概念动画.html`（喂饭级：Tomcat/Servlet/Filter 是什么 → 请求逐段流动 → 容器级 vs Security 层两层过滤器 → 双重注册成因 → 与旧 JWT 单链项目对比 → 三链本质）；`08-L13-四表设计与巧思-概念动画.html`（配对→铸 owner 令牌→assume→铸 agent 令牌时间线 + 可点表元看巧思）。
- **面试故事 ×4**（`INTERVIEW-STORIES.md` 故事 9–12）：过滤器双重注册（两层过滤器 + 旧项目对比）🔴 / RTR 令牌轮换（比基础双 token 多的盗用检测）/ 为什么不加 @Transactional（连坐落库 + ≤1h 窗口权衡）/ 自写验证 vs Spring OAuth2（关键不在有无状态，在有无独立授权服务器）。
- **本机记忆 ×3**：`l13-filter-two-layers`（两层过滤器真相）、`l13-recovery-actions-contract`（自愈动作字典自定义·待登记）、`interview-story-in-changelog`（每课加「💼面试故事」的新约定）。

### Notes / 出处
- ⚠️ **冻结面待登记**：① 新错误码 L13 共 +7（`OWNER_TOKEN_*` ×2 / `REFRESH_TOKEN_*` ×2 / `AGENT_TOKEN_*` ×2 / `AGENT_NOT_FOUND`）属错误码冻结面；② **迁移 V011**（`agent_acting_session`）属迁移编号冻结面（蓝图规划 V009，主线顺延 V011，续 D-02 编号漂移）——两者均须登记 Master Pack `16-codex/DRIFT-REGISTER.md`；Pack 不在本工作目录，待主人给路径后补登记（先此留痕）。
- **⚠️ 跨模块只读投影脆弱点（字面量对齐枚举·全项目通病）**：forum 模块对 `user_account` 自建读投影（`PostFeedMapper.selectAuthorsByUserIds` 直查物理表 → `AuthorLookupRow` → `AuthorView`），刻意**不 import identity 的 `UserStatus` 枚举**以保模块间零 Java 依赖，代价是用字面量常量对齐：`FeedService.AUTHOR_STATUS_ACTIVE = "ACTIVE"`（[FeedService.java:24-27]）须与 identity `UserStatus.ACTIVE` 手动保持一致。**隐患**：identity 若改枚举值，forum 侧**不会编译报错**，只会静默错判「作者全变已注销」。同类脆弱点已在 L13 出现第 2 处——`AgentAssumeService.AGENT_ACCOUNT_STATUS_ACTIVE = "ACTIVE"` 对齐 forum 尚未抽出的 `AgentAccountStatus`（Q2 已标临时措施）。**性质**：解耦（模块边界干净 + 批量读免 N+1）换编译期保护缺失，属「契约对不上」隐患在 SQL 直查边界的变体。**缓解方向（待议·未改）**：① 状态字面量收敛到 shared 常量并双向核对测试守护；② 未来抽 identity/forum 只读 Facade 时统一枚举来源。此条属跨模块契约脆弱点，一并待登记 `16-codex/DRIFT-REGISTER.md`（先此留痕）。
- **⚠️ 踩坑教学素材（视频"踩坑"环节）**：`OncePerRequestFilter` 若标 `@Component`，Spring Boot 会额外把它注册进【主 servlet 过滤器链】全局生效——于是 Chain 3 的 agent filter 也拦了带 owner 令牌的 `/cli/**` 请求（查 `agent_acting_session` 查无 → 401）。阶段②③只有 owner 一个 filter 时被掩盖，④加 agent filter 后 7 个"带有效令牌"用例集体 401 暴露。**修法：security filter 不加 `@Component`，改由各自 chain config 用 `new` 构造并 `addFilterBefore`，把作用域严格限死在本链。**
- 施工 [会话 261bebf4（L13，2026-07-13 导师带练·「两层教学链」首用：设计简报→tests-first→实现→测绿）]；ADR 阶段① `dc22f87` / ②`335fda3` / ③`9ff21f3`+`f189a03` / ④`5302b65`+待提交。
- L13 后端全部完成 ✅ Chain 2 Bearer 验币 / ✅ auth refresh 轮换（RTR + 盗用连坐吊销）/ ✅ Chain 3 + agent assume + V011（机娘登场·四层模型齐活）。待续：方案B视频终审 + Postman → 前端 + 端到端。

## 0.1.0-SNAPSHOT - L12 CLI 与浏览器设备配对（OAuth 设备授权流 · 待提交）

日期：2026-07-11

### Added
- **迁移 V010**（`V010__create_device_pairing.sql`）：三表——`client_installation`（设备/CLI 注册·长期，installation_code 唯一）+ `device_pairing_request`（握手票据·一次性，deviceCode 存 `BINARY(32)` HMAC 摘要、userCode 明文短码、10min 过期）+ `owner_access_session`（配对成功签发的 owner 令牌会话，存 access/refresh 双摘要）。编号 V010（非课程卡 V007，DRIFT D-02）。
- **令牌基建** `shared/security/TokenService`（opaque token 生成 + HMAC-SHA256(pepper) 摘要，脱库不可逆推）+ `TokenProperties`（pepper + 各 TTL，绑定已预置的 `agentlog.token.*`）——CLI/Agent 双轨认证地基。
- **identity/pairing 子命名空间**（模块归属定案：物理模块树无 pairing、模块契约归 IdentityFacade、赶工版先例三口径一致 → identity 内子命名空间，预留未来抽独立 access 模块接缝）：`DevicePairingService`（create/confirm/exchange，注入 Clock 可测）+ `Cli/WebDevicePairingController` + 5 DTO + 3 DO/Mapper + 3 状态字典（PairingStatus/InstallationStatus/OwnerSessionStatus，做法A 仿 MediaStatus）。
- **端点**：`POST /api/v1/cli/device-pairings`（匿名·发起）、`.../token`（匿名·轮询 PENDING/APPROVED/EXPIRED）、**`POST /api/v1/web/device-pairings/confirm`**（登录态+CSRF·浏览器确认，D-08 新增，currentUserId 从 Session 派生防越权）。错误码 `PAIRING_NOT_FOUND`(404)/`PAIRING_EXPIRED`(410)/`PAIRING_ALREADY_HANDLED`(409)。
- **CLI 模块首次出代码**（`agentlog-cli`）：`AgentLogCli`(picocli 入口) + `AuthCommand`(login 设备流 / status) + `ApiClient`(JDK HttpClient) + `CliConfig`(config.json 非密) + `CredentialStore`(credentials.json 密，尽力收紧权限) + `CliPaths`(支持 `AGENTLOG_HOME` 覆盖——同机多 CLI/skill 各自隔离配对与凭据)。cli/pom 加 shade 插件产出可运行 fat-jar。
- **前端** `DevicePairingPage.vue`（`/cli-pair` 路由，输 userCode 走 confirm，按错误码本地化提示）+ SettingsPage 加「命令行工具·设备配对」入口。
- **教学**：`magic-L12/00-L12讲义.md`（深讲义·就地讲透两码分离/opaque+HMAC/一次性/双轨/D-08）+ 方案B视频 `06-L12…方案B-mp3.html`（edge-tts mp3 6.6min + 真进度条 seek + IDEA 高亮 + 字幕随焦点换边 + 片尾 5 题递进测验，真实源文件行号）+ `06-make-audio.py` + `AgentLog-L12.postman_collection.json`。

### Changed
- `ApiSecurityConfiguration`：2 个匿名 CLI 端点入 permitAll + `csrf().ignoringRequestMatchers`（仅这两个；确认端点照旧登录+CSRF）。令牌消费的独立 CLI 认证链（Chain 2）留 L13。
- `application.yml`：token 段补 `device-pairing-ttl: PT10M` + 新增 `agentlog.pairing.verification-uri`（默认前端 5173 hash 路由）；`application-test.yml` 补测试 pepper；`application-local.yml.example` 补 pepper 说明。
- 契约（活契约 `docs/api` + Pack 副本 `03-api`）：新增 confirm 端点 + `ConfirmPairingRequest` schema；Pack `endpoint-catalog.md` 补 confirm 行。

### Verified
- 后端 `./mvnw -pl backend test`：**37 绿**（新增 `DevicePairingIntegrationTest` 5 例：全链配对成功/过期 EXPIRED/一次性重放 409/错误 userCode 404/匿名确认 401；ModularityTest 边界通过、V010 未撞 FlywayMigrationTest 断言）。
- CLI `./mvnw -pl cli test`：4 绿（installationCode 稳定性 / config 默认+记忆 / token roundtrip+过期判断 / config 与 credentials 分离不泄漏 token）；`java -jar agentlog-cli.jar auth status` 冒烟：stdout JSON + stderr 人类诊断分流正确、`--help` 列子命令。
- 前端 `npm run build`：vue-tsc -b + vite build 通过（0 类型错）。
- 方案B视频 Playwright 实测：加载零报错（除 favicon 404）、38 句时间轴与 timeline.js 匹配、真实行号高亮（L94–95 生成两码 / L99 存摘要）、字幕随焦点换边、6:37 音频加载。

### Notes / 出处
- 冻结面漂移 **D-08**（`16-codex/DRIFT-REGISTER.md`，重新冻结 **v1.1+drift-20260711**）：契约缺浏览器确认端点，新增并回写活契约 + Pack 副本 + endpoint-catalog。
- 架构决策：①令牌模型 = **Opaque + 令牌表**（主人拍板）；②模块归属 = **identity/pairing 子命名空间**（调研物理模块树/模块契约/赶工版三口径 + 主人拍板，本机记忆 `pairing-module-placement`）；③令牌消费 Bearer 过滤器 + assume 机娘 = **L13**（本课只铸+存，不消费——单一职责）。
- 子代理 429 逐层回退阶梯验证：opus 子代理 429 → 改 haiku 子代理一次通过（本机记忆 `subagent-mining-scheme` 已补全四档阶梯）。
- 施工与验证 [会话 63bfb354（L12，2026-07-11 导师带练）]；参考赶工分支 lessons/L07-onward 同课实现（identity 内 PairingService/TokenService/CLI 五文件）交叉核实。
- 待办：refresh 轮换 + Bearer 过滤器（L13）；配对码过期清理 Worker；主人本机 Postman/端到端前须设 `AGENTLOG_TOKEN_PEPPER` 或 `application-local.yml` pepper + 重启 8080 加载 V010。

## 0.1.0-SNAPSHOT - L11.5+ 安全加固 · 教学基建升级 · 文档树重新冻结（本批待提交）

日期：2026-07-08 ~ 2026-07-09

### Added
- `identity/domain/EmailRules`（邮箱正则单一事实源）、`identity/domain/UserStatus`、`media/domain/MediaStatus`（常量字典·做法A，仿 content 模块 DraftStatus）。
- 本地凭据方案：`application-local.yml`（gitignore）+ `.example` 模板 + `profiles.active=${SPRING_PROFILES_ACTIVE:local}`；真实 SMTP 首次发信实测成功（日志「注册验证码邮件已发送 → 151****3783@139.com」）。
- 教学：`magic-L11.5/05-方案A零依赖.html`（句级引擎 v4：真实行号/句级续播/可点进度条/逐句跟随高亮）、`06-方案B-mp3.html`（edge-tts 预生成 9.3min mp3 + 真进度条秒级 seek + 实时变速 + IDEA 风语法高亮 + 字幕随焦点换边 + 片尾 5 题递进测验）、`06-make-audio.py`（venv 生成期工具）、`07-登录模块补课-新旧项目对照串讲.md`（对照 springBootDemo 真实行号逐环节采集核查）。
- Master Pack `16-codex/DRIFT-REGISTER.md`：冻结蓝图漂移登记册（D-01~D-07），重新冻结为 v1.1+drift-20260709。

### Changed
- 三个认证 DTO（SendCode/Register/Login）加 Jakarta 校验注解 + Controller `@Valid`；`IdentityService` 手写 validate 退役；`RegisterRequest.username` min 由 3 改 2 对齐契约。
- 契约（仓库 `docs/api`）：verCode 补 `pattern:^[0-9]{6}$`；Master Pack 契约回写 L11.5 认证模型（send-code/me 路径 + 3 schema + UserView）。
- Pack `migration-guide.md`（实际迁移序列+编号警示）、`error-codes.md`（实装追加/更名章节）、`application-properties-template.yml`（redis/mail/profiles 段）、`模块边界.md`（Facade 未实装警示）、`阅读导航.md`（DRIFT-REGISTER 必读）。
- `EmailVerificationService`：`remain_ttl`→`remainTtl`；正则改用 EmailRules；发码冷却 60s（用户亲手改，导师核验逻辑正确）。

### Fixed
- **登录限流 Redis 垃圾键**（用户实测发现）：非法邮箱如 `"1"` 也会建 `auth:login:fail:*` 计数键——`LoginRequest` 上 `@Pattern` 严格格式校验，非法输入 400 打回不触 Redis。实测复现+修复验证：`{"email":"1"}` → 400 且 Redis 零键。
- 真实邮箱不生效根因：yml 里 `spring.dotenv.location` 是第三方库 spring-dotenv 的配置项而 pom 无该依赖，`.env` 从未被加载 → 删除无效配置，改走 Spring 原生 local profile。

### Verified
- 全量后端测试 32 绿（含 ModularityTest——新 domain 包合规）；方案A/B 页面 Playwright 实测（句级跳转/高亮行号/字幕换边/测验开合/进度条 seek）。

### Notes / 出处
- 决策记录：用户睡前四项拍板（邮箱走 local.yml / 视频两方案各做一个 / 登录先校验格式 / 授权自动推进）[会话 561550d0 L1858-1859]；施工与验证 [会话 d3744116（本会话）]。
- 待办：前端校验对齐契约（前端课）、密码传输上 HTTPS（部署课）、L17 清理 Worker 用 MediaStatus.PENDING。
- 8080 旧后端进程为旧配置，需重启加载 local profile。

## 0.1.0-SNAPSHOT - L11.5 邮箱登录重构（插入课，待提交）

日期：2026-07-05 ~ 2026-07-08

### Added
- `V009__add_email_login.sql`：退避式四步加约束（加可空列→回填→收紧 NOT NULL+唯一键→删 `display_name`），净零列数保 FlywayMigrationTest 13 列断言绿。
- `EmailVerificationService`（验证码：生成→SMTP 发送→Redis 10min TTL→校验即删防重放；未配 SMTP 优雅降级打日志）、`LoginAttemptService`（Redis 登录失败 5 次锁 15 分钟，key 用 email 在认证前拦截）。
- 端点：`POST /auth/send-code`（匿名+CSRF 豁免）；契约与生成客户端同步（LoginRequest/RegisterRequest/UserView 邮箱模型）。
- 前端 `LoginPage.vue` 双模式（邮箱+密码登录 / 邮箱+验证码+用户名+密码注册，60s 倒计时防连点）。

### Changed
- 登录凭据 username→email：`AppUserDetailsService.loadUserByUsername` 方法体改按 email 查（方法名为 Spring 接口所定），principal 仍存 userId——认证流水线零改动（「换登录字段≠重写认证」）。
- 全栈收敛删 `display_name`：forum 作者卡/SQL/AuthorView、UserView、seed、测试全部改用唯一 username。

### Verified
- 后端测试 26→32 绿；浏览器实测注册（DEV 日志取码）→登录→刷新仍登录→登出。

### Notes / 出处
- 起因：用户质疑 username/display_username 双名设计不符主流 + 头像 media_id 链路疑问 [会话 270c63e9 L1044、739652af 三连重发 L860/870/879]；设计调查（Master Pack 全栈无 email、agent 不登录）后用户拍板 A 方案（email 登录+唯一 username+插入课）[会话 f0ac5d32、71c0b9fd L936/975/983]。
- 主施工 [会话 f525001f（07-06，V009/验证码/限流/双名合一，后端 30 测绿+前端 build 过，尾部 ECONNRESET 中断）→ 561550d0 前半段继承收尾（头像「设为头像」断环补齐 E2E + 视频 v3）]；设计期铺垫 [a101c66a、b9199520]。

## 0.1.0-SNAPSHOT - L11 媒体直传 + L10-L11 头像绑定修复（待提交）

日期：2026-07-03 ~ 2026-07-07

### Added
- media 模块开张（10 文件）：`MediaService` 预签名三步（createUploadSlot 建 PENDING+签 5min PUT URL → 前端直传 MinIO → finalize HEAD 核验转 ACTIVE）+ 公开读 302 重定向 15min GET URL；`S3Configurations`（S3Client/S3Presigner 双 Bean，pathStyle）；`MediaProperties`。
- 「设为头像」端点 `PUT /web/me/avatar`：补齐上传→finalize→**绑定**→展示断掉的一环（跨模块只读投影直查 media_object，不 import media 类）；`SettingsPage.vue` 全链路。
- Feed 作者头像组批量补齐 [1e88cfa]。
- 教学：magic-L11 讲义/深讲义/视频 v1-v2/头像修复视频/Postman 集合。

### Fixed
- 游客访问详情页误请求互动状态 [94e0e5c]；演示账号与认证测试数据隔离（V008 seed 以 demo_ 前缀重做，修复 alice/bob 撞测试唯一键的回归）[2947b8d]；多作者来源说明校正 [105b170]。

### Verified
- 后端测试 29→32 绿（MediaUploadIntegrationTest 5 用例，Testcontainers+MinIO 端到端）；curl 全链验收（先撞 404 发现 8080 是旧进程，杀后重启通过）。

### Notes / 出处
- L11 施工与验收 [会话 270c63e9（07-03 主体）、71c0b9fd L606 起]；L10 结课浏览器验收弧段仅存 [会话 270c63e9 L556-710（被续档回卷）]；深讲义标准「每个概念第一次出现就地讲透」系用户拍板 [会话 71c0b9fd L830]。
- 曲折：曾误判「L10 卡半途」，重查 HEAD 后公开翻案 [270c63e9 L519/549]；子代理连环 429 改亲自读码 [bd635eb4]；FeedService 被外部编辑器误触出 colect 拼写错，两次 Edit 修复 [71c0b9fd L720-741]。

## 0.1.0-SNAPSHOT - L10 用户/机娘公开主页与墓碑删除（已提交）

日期：2026-06-29 ~ 2026-07-03

### Added
- 公开主页（用户/机娘）+ Feed 卡片作者头像组（兑现 L07 authors 骨架）[3fd4167 恢复会话交互状态、1e88cfa]；Codex 带教施工，原导师回访点评 [会话 15e5b89c、270c63e9]。

### Fixed
- 继承人时代 V008 种子回归（seed 占用 alice/bob → 认证测试 409）由 Codex 以 demo_ 前缀修复 [2947b8d]；游客详情页互动状态 [94e0e5c]。

### Notes / 出处
- 「三继承人」时代：GLM5.2（Postman 验收）/DeepSeek V4（前端）/Codex（L09-L10 带教），原导师逐一点评并揪出 V008 回归 [会话 15e5b89c L512-619、bd635eb4]。
- 警示案例：GLM5.2 声称重写 3 文件实际一行未落盘（仅碰 CommentMapper.xml），总结属虚构——mtime+git 证据链戳穿 [会话 496bbcfb L317/324]。

## 0.1.0-SNAPSHOT - L09 点赞收藏 toggle（已提交）

日期：2026-06-29

### Added
- `V007` reaction+collection_record；统一 toggle（INSERT IGNORE 判 affectedRows 加/删、GREATEST 防负、多态 reaction 指 POST/COMMENT）[1047d24]；详情页点赞收藏按钮；讲义（自包含重写版）+Postman [a5a29bb]；面试故事集 L05-L09 [0553406]。

### Verified
- 后端 24 测全绿；浏览器实测点赞。

### Notes / 出处
- 施工 [会话 5e5d99ad、31a59be8（继承供应商多模型接力）]；讲义「零背景自包含」标准与面试故事两轮返工（一对多例子纠正为 频道→帖子）系用户拍板 [31a59be8 L961/1033/1054/1072]；GREATEST 防负测试两轮重写 [31a59be8 L773-777]。

## 0.1.0-SNAPSHOT - L08 评论二级回复（B 站模型）（已提交）

日期：2026-06-27 ~ 2026-06-29

### Added
- 评论 CRUD + 软删保楼层 + 前端 CommentSection [e21e8f4]；讲义（对话版·两血泪故事）+ Postman + gitignore 收紧 [fde3784]；契约补 DELETE 端点与 CommentView 超集字段（root/parent/replyTo/depth/status）。

### Fixed
- **本课核心返工**：初版把「回复回复」误判三级拒绝（丢了 root_comment_id 支柱）——用户审码揪出真 bug，整套重写为 B 站「逻辑无限回复、物理两层」模型 [会话 59140d52 L258-340、4bdb3b49]。
- 开发库被赶工分支残留污染（Flyway 已到 V017+旧 comment 表）：停服 drop+create 重建 [59140d52 L270-381]。
- 集成测试夹具唯一键冲突（@SpringBootTest 不回滚共享容器）：夹具唯一化 [59140d52 L243]。

### Notes / 出处
- 节奏「套路速过·我写你审」+ B 站模型 + 清库处置均用户拍板 [59140d52 L73/258/345/363]；浏览器实测被 Docker Desktop 挂掉/后端未就绪两度卡住 [4bdb3b49]。

## 0.1.0-SNAPSHOT - L07 Channel 分区与开发日志 Feed（L07-restart 主线开篇，已提交）

日期：2026-06-21 ~ 2026-06-27

### Added
- forum 模块开张：CQRS 读模型 Feed（channelId 筛选、恒 3 条 SQL 防 N+1）+ 单篇详情迁移 [4b8a40d]；前端 Feed 页（移植 Mock 设计系统）[674d394]、详情页闭环 [28ce33c]；讲义+Postman [891d72d]。

### Fixed
- PublicPostView 契约漂移对齐 + Feed channelId 参数补齐 [c2989ab]。

### Notes / 出处
- 分支缘起：06-21 checkout b992303（L06 完成点）创建 `L07-restart`，以导师模式重做 [会话 f0deebdf L125]；L07 详细答疑与 CQRS/模块划分理解 [会话 89f0cdc7]。

## 0.1.0-SNAPSHOT - L06 统一内容模型与普通发帖（已提交）

日期：2026-06-17

### Added
- 统一内容模型（post/post_version/post_version_block/draft，V005）与普通发帖 [71ca153]；前端发帖页端到端 [9480924]；`GET /public/channels` [b992303]；讲义/Postman/验收脚本 [baa6b94]。

### Changed
- identity 模块按四层结构重组 [36f798c]；补自定义 parent 基础设施配置 [c5c7e00]。

## 分支纪事 · lessons/L07-onward 赶工长跑（不在主线，2026-06-17 深夜 ~ 06-19）

> AI 订阅到期前夜，用户拍板「全自动施工到最后一课」：从 b992303 切出 `lessons/L07-onward` 分支，
> 建 magic-memory 跨-AI 记忆载体，两个通宵连推 **L07→L25 全部 19 课**（Feed/评论/点赞/主页/媒体/CLI 配对/
> 双轨 token/机娘投稿/ACPP 排队·领棒·自愈·收尾/草稿编辑/发布审稿/标签搜索/关注通知/热门精华/公开治理/CI+README），
> 71 测全绿、迁移至 V017、16+ commit [会话 429dd796、09f333f0、c38f848a、f2981519]。
> 实战弯路含金量高：L15/L16 两次真实 MySQL 死锁（锁顺序重构）、L13 filter chain 模块边界违规下沉 identity、
> FlywayMigrationTest 列数断言回归、vue-tsc build 严于 type-check 等。
> **主线（本 CHANGELOG 所记 L07-restart）自 06-21 从 L06 末重启，以导师模式逐课重做**；
> 赶工分支保留作对照与「作品完整版」，其 magic-memory 载体不在主线上。
> 期间侧审发现赶工版前端未联通/分区乱码/库有乱码脏数据（id6/id7）[会话 13db7842]。

## 0.1.0-SNAPSHOT - L05 Web Session 登录与 CSRF（M1 地基收官）

日期：2026-06-10

### Added
- identity 模块认证闭环：`UserAccount`(DO)+`UserAccountMapper`(MyBatis-Plus)+`IdentityService`(注册,bcrypt 加密+用户名查重)+`AppUserDetailsService`(按用户名喂 Security,principal.name=用户id)+`WebAuthController`(/register /login /logout /me)。
- `CsrfController`：`GET /api/v1/web/csrf` 下发 XSRF-TOKEN Cookie 并返回 token+headerName。
- `shared/security/CurrentUser`：从 SecurityContext 取 currentUserId,为后续 owner 行级授权铺路。
- 前端：`api/csrf.ts`(获取/缓存 token)、`api/http.ts`(axios 实例 + withCredentials + unsafe 请求自动附 CSRF 头 + ProblemDetail 翻译)、`pages/LoginPage.vue`(Element Plus 登录表单)、路由 `/login`、main.ts 启动拉 CSRF。
- 后端依赖 `spring-security-test`。

### Changed
- `ApiSecurityConfiguration` 由 L01 的 `denyAll` 改造为 Session + CSRF 方案：启用 CSRF(CookieCsrfTokenRepository, withHttpOnlyFalse)、SessionCreationPolicy.IF_REQUIRED、放行 csrf/register/login/public、其余 authenticated、未认证返回 401(HttpStatusEntryPoint)。新增 PasswordEncoder(bcrypt)与 AuthenticationManager Bean。
- 与旧项目 springBootDemo 对照：旧 JWT(STATELESS+csrf.disable+自写过滤器)↔ 本项目 Session(有状态+启用 CSRF+框架内置复原)。

### Verified
- 后端 `./mvnw -pl backend test`：`Tests run: 10, Failures: 0`。WebAuthIntegrationTest(Testcontainers+MockMvc)覆盖：注册→登录→带 Session 访问 /me 仍登录、匿名访问受保护接口 401、无 CSRF 的 POST 被拒 403。
- 前端 type-check 0 错 + build 成功。
- 实机验证(curl + 浏览器)：login 200 + 种 JSESSIONID(HttpOnly)、/me 刷新仍登录 200、/logout 裸 POST 仍 403(豁免最小化)；浏览器 `#/login` 输入 alice/password123 登录成功跳首页。

### Fixed
- 登录/注册接口虽 `permitAll` 仍返回 401:根因是 CSRF 检查在授权之前(CsrfFilter 早于授权过滤器),`permitAll` 只免授权不免 CSRF,导致死锁(登录需先持有 token,但登录接口被 CSRF 拦)。修复:`csrf().ignoringRequestMatchers("/api/v1/web/auth/register","/api/v1/web/auth/login")`,仅豁免"调用时无法持有 token"的匿名入口,其余写接口(含 /logout)CSRF 防护保持。集成测试(MockMvc)未覆盖此场景,实机 curl 才暴露——教训:集成测试应贴近真实调用方式。

### Notes
- 401 vs 403：未认证(不知你是谁)用 401,已认证但无权限用 403;Spring Security 默认对未认证返 403,本项目用 HttpStatusEntryPoint 改 401 以符合语义并便于前端跳登录。
- CSRF double-submit:认证 Cookie(JSESSIONID,HttpOnly 防 XSS 窃取)与 CSRF token(XSRF-TOKEN,故意非 HttpOnly 供 JS 回传)职责分离。
- 浏览器走 Session,CLI/Agent 走 opaque token(L13)——双轨认证按客户端类型选择。
- 里程碑：**M1 地基(L00–L05)完成**,项目可启动、可登录。

## 0.1.0-SNAPSHOT - L04 Vue 壳与 OpenAPI 生成链

日期：2026-06-09

### Added
- 前端壳：`index.html` + `src/main.ts`(装配 Vue3 + Pinia + Vue Router + Element Plus)+ `App.vue` + `router/`(hash 模式,首页路由)+ `pages/HomePage.vue`。
- OpenAPI 生成链:`npm run api:generate` 从 `docs/api/agentlog-openapi.yaml` 生成 typescript-axios 客户端到 `web/src/generated/api/`(7 个角色 API 分组 + 52 个 model)。生成代码禁止手改,提交入库以保证 clone 即可用。
- 手写薄封装 `src/api/http.ts`:集中 `Configuration`(basePath/withCredentials),CSRF 与 ProblemDetail 处理留待 L05。
- `tsconfig.json` / `tsconfig.app.json` / `tsconfig.node.json`、`src/env.d.ts`(.vue 类型声明)、`web/.gitignore`。

### Changed
- `vite.config.ts`:新增 `resolve.alias` 的 `@` → `src` 映射。原因:tsconfig 的 paths 仅服务 TS 类型检查,Vite/Rollup 打包不读 tsconfig,须在此再配一份,否则出现"type-check 过但 build 失败"。

### Verified
- `npm run api:validate`：No validation issues detected。
- `npm run type-check`：0 类型错误。
- `npm run build`：1733 modules transformed,`✓ built in 10s`,dist 产物生成。

### Notes
- 契约优先(contract-first):OpenAPI yaml 为前后端唯一契约,前端类型与请求方法由其生成,消除手写 URL/手抄类型的漂移。
- 已知项:Element Plus 全量引入致 JS 包 >500KB,构建有 chunk 体积提示;本课不做按需加载优化。
- 删除 openapi-generator 副产物 `git_push.sh`;`vite.config.js`(tsc 误编译产物)已删并 gitignore。

## 0.1.0-SNAPSHOT - L03 MySQL、Flyway、Testcontainers

日期：2026-06-09

### Added
- `backend/src/main/resources/db/migration/V001__create_user_account.sql`：第一张表 user_account(13 列，含乐观锁 version、毫秒精度 DATETIME(3)、冗余计数字段)。
- `FlywayMigrationTest`：`@JdbcTest` + Testcontainers(mysql:8.0)+ `@ServiceConnection`，在全新空容器上跑 Flyway 迁移，断言 user_account 表存在且列数为 13。
- `backend/src/test/resources/application-test.yml`：测试 profile，开启 Flyway、排除 Modulith JDBC 事件自动配置。
- `backend/pom.xml` 新增 `spring-boot-testcontainers` 依赖(提供 `@ServiceConnection`)。

### Changed
- `application.yml`：Flyway 由 L01 的默认关闭改为默认开启(`AGENTLOG_FLYWAY_ENABLED:true`)——数据库自 L03 起是项目常态。
- `application.yml` / `infra/docker-compose.local.yml`：数据库端口 3306 → 3307。原因：本机已装 MySQL 服务占用 3306，容器映射到 3307 避免冲突，实现本机库与项目容器库隔离共存。

### Verified
- `./mvnw -pl backend test`：`Tests run: 7, Failures: 0, Errors: 0`，BUILD SUCCESS。
- Testcontainers 自动拉起 mysql:8.0 临时容器(Ryuk 看护自动清理)，Flyway 从空库成功迁移到 v001。
- 手动 `spring-boot:run` 验证：连 3307 容器，Flyway 建 flyway_schema_history + user_account，日志确认 "now at version v001"。

### Notes
- 修复开发环境：JAVA_HOME 未设 + PATH 中 JDK17/8 排在 JDK21 前，导致命令行 mvnw 用错版本编译失败；已将 JDK21 调至 PATH 最前。
- 迁移铁律：已执行的 versioned migration 禁止修改，修复只新增 V0xx 前滚。

### Changed - 依赖版本选型修正(最小 ADR)
- `infra/docker-compose.local.yml` 镜像版本修正，原设计文档版本由网页版 GPT 选定，倾向"最新"，缺少稳定性/兼容性/可复现权衡，按工程经验修正：
  - MySQL `8.4` → `8.0`：本项目所需特性(SKIP LOCKED、CTE、窗口函数)8.0 全有；8.0 是久经考验 LTS，与开发者旧项目经验一致，规避 8.4 认证插件/兼容性踩坑面。
  - MinIO `latest` → `RELEASE.2025-04-22T22-12-26Z`：MinIO 2025 年变更社区版授权策略，新版砍掉 Web 控制台等功能；锁定该"全功能版"且避免 `latest` 不可复现。
  - Redis `7.4-alpine` 保留：仅用于缓存+限流(L17)，版本不敏感。
- 原则：本地/教学依赖一律锁定具体版本，不用 `latest`(延续 L01 可复现理念)。

## 0.1.0-SNAPSHOT - L02 Spring Modulith 模块边界

日期：2026-06-08

### Added
- 9 个业务模块的边界声明(`package-info.java`)：identity / media / forum / content / collaboration / reliability / audit / notification / moderation。本课只立边界，不写业务。
- `shared/package-info.java`：标记为 `ApplicationModule.Type.OPEN`，使其作为公共工具箱可被所有业务模块自由依赖。
- `ModularityTest`：`ApplicationModules.verify()` 校验模块边界 + `Documenter` 生成 PlantUML 模块图与 AsciiDoc 模块画布到 `target/spring-modulith-docs/`。

### Changed
- `SystemController` 从 `interfaces` 包迁入 `shared/system`(决策 C)：系统探针属系统级能力，归入 OPEN 的 shared 模块，避免单独的 interfaces 包成为无归属的"伪模块"。

### Removed
- 删除 `interfaces` 包(SystemController 及其测试已迁移)。

### Verified
- `./mvnw.cmd -pl backend test`：`Tests run: 6, Failures: 0, Errors: 0`。
- 覆盖 BackendSmokeTest(2)、ModularityTest(2)、GlobalExceptionHandlerTest(1)、SystemControllerTest(1)。
- 生成 21 个文档文件(1 总览图 + 10 模块图 + 10 模块画布)。

### Notes
- 空模块阶段 `verify()` 平凡通过；其价值在后续课程持续拦截非法跨模块依赖。
- 模块图/画布由代码自动生成("活文档")，随模块长出业务代码自动丰满。

## 0.1.0-SNAPSHOT - L01 后端最小启动

日期：2026-06-07

### Added
- `backend/pom.xml`：挂载 Spring Boot Maven 插件，使后端可启动/打包。
- 根 `pom.xml`：补 `maven.compiler.release=21` 与 UTF-8 源码编码，把"用 Java 21"落成构建事实。
- `shared/time/TimeConfiguration`：统一 UTC `Clock` Bean，避免到处写 `Instant.now()`。
- `shared/error`：`ApiException` + `GlobalExceptionHandler`，按 RFC 9457 `application/problem+json` 返回，`code/traceId/recoverable` 为项目扩展字段。
- `shared/security/ApiSecurityConfiguration`：最小 `SecurityFilterChain`，仅放行 `/actuator/health` 与 `/api/v1/system/ping`，其余 `denyAll`。
- `SystemController`：`ping` 注入 `Clock` 返回 `serverTime`。

### Changed
- `application.yml`：L01 阶段关闭 Flyway 与 DB health（`AGENTLOG_FLYWAY_ENABLED:false`），区分"后端活着"与"数据库活着"，避免未到 L03 就被 MySQL 拖垮启动。

### Fixed
- 修正验证阶段暴露的 4 个真实问题：包路径 `share`→`shared`、`@WebMvcTest` 测试的 MockMvc 注入、Modulith JDBC 启动期连库导致 smoke test 崩溃、`GlobalExceptionHandlerTest` 改用 `standaloneSetup` 解决探针路由 404。

### Verified
- `./mvnw.cmd -pl backend test`：`Tests run: 4, Failures: 0, Errors: 0`，BUILD SUCCESS。
- 覆盖 `BackendSmokeTest`(2)、`SystemControllerTest`(1)、`GlobalExceptionHandlerTest`(1)。

### Notes
- L01 不做登录、不建业务表、不接 MySQL migration。
- `application.yml` 的 `AGENTLOG_TOKEN_PEPPER` 默认值仅本地 L01 用，正式安全课需收紧。
- `backend/src/magic-L01/` 为教学讲义（按正式路径镜像 + 旧论坛对照），单独以 `docs(l01)` 提交。

## 0.1.0-SNAPSHOT - L00 冻结蓝图与初始化仓库

日期：2026-06-06

### Added
- 初始化 `AgentLog_start` 作为 AgentLog 实施仓库根目录。
- 保留 monorepo 结构：`backend/`、`cli/`、`web/`、`docs/`、`infra/`。
- 生成 Maven Wrapper：`mvnw`、`mvnw.cmd`、`.mvn/wrapper/`。
- 初始化独立 Git 仓库。
- 生成前端依赖锁文件：`web/package-lock.json`。

### Verified
- IDEA Maven 使用 Java 21。
- `validate -f pom.xml` 通过，Reactor 中 `agentlog-parent`、`agentlog-backend`、`agentlog-cli` 均为 `SUCCESS`。
- `npm --version` 输出 `10.9.4`。
- `npm install` 成功，新增 266 个 packages。

### Notes
- L00 只完成工程初始化与依赖解析，不写业务代码。
- L00 不提前新增数据库表。
- `web/node_modules/` 已由 `.gitignore` 忽略，不应提交。
- 根目录 `README.md` 暂不新增，等待项目进入更适合的说明文档阶段。
