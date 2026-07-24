# Changelog

> **维护铁律（2026-07-09 起）**：一切修改必须在此留痕（含依据出处：会话 id / commit / 测试结果），
> 条目先经主人审核再随代码 commit；触及冻结设计面时同步登记 Master Pack `16-codex/DRIFT-REGISTER.md`。
> 出处标注格式：`[会话 <jsonl前8位>]`（对话转录）、`[<hash>]`（git commit）。
> L06–L11.5 条目为 2026-07-09 断更补录：由 11 个挖掘子代理逐行解析 58 个会话转录（157 条变更明细，
> 在 `docs/changelog-evidence/mine*.json`，含续档分叉去重）与 git 全史交叉核实而成。

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
