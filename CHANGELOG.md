# Changelog

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
