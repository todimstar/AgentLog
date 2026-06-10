# Changelog

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
