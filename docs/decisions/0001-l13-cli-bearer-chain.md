# ADR-0001 · CLI Bearer 认证链（Chain 2）消费 OwnerAccessToken

- **状态**：已采纳（2026-07-13 · L13 设计简报① · 苏格拉底式，主人拍板两处岔路）
- **关联**：L12 铸币（V010 `owner_access_session`）→ L13 消费；[[teaching-methodology]] ② 决策留痕

## 背景 / 问题
L12 铸了 OwnerAccessToken（库存 `access_token_digest`），但**无消费端**。CLI 持 Bearer 令牌访问 `/api/v1/cli/**` 业务接口，撞上唯一的 Session 链（`ApiSecurityConfiguration`）判无会话 → 401。需一条**只认 Bearer** 的独立安全链把令牌"花掉"。

## 决策
1. **多链共存**：新增 Chain 2，`securityMatcher=/api/v1/cli/**`，`STATELESS` + `csrf.disable()`；`@Order` 比 Chain1(Session, 无 matcher 兜底) 更靠前——最具体优先。
2. **自写 `OwnerBearerAuthenticationFilter`（OncePerRequestFilter）**，不引 OAuth2 Resource Server：令牌是 opaque + 自有令牌表，无 JWT/introspection 需求；自写直接对接 `owner_access_session`，透明可控。（印证 L12 在 `TokenService:23`、`OwnerAccessSessionDO:11`、`OwnerSessionStatus:6` 预埋的设计意图。）
3. **验币复用 `TokenService.digest`**（HMAC-SHA256(pepper)）：Header `Bearer <明文>` → digest → 按 `accessTokenDigest` 查表 → `status=ACTIVE` 且 `accessExpiresAt>now` → 认证。铸/验同算法，否则对不上。
4. **principal 粒度 = {ownerUserId + installationId}**（主人拍板）：为多设备审计 / Chain3 `assume` 按 installation 隔离机娘留钩子。
5. **错误语义分层**（主人拍板·新错误码·均 401）：
   - 无 `Authorization` 头 → 交 entryPoint 通用 401（未认证，"你都没带令牌"）。
   - `OWNER_TOKEN_EXPIRED`：命中但 `accessExpiresAt` 已过 → 提示去 `refresh`。
   - `OWNER_TOKEN_INVALID`：digest 无命中 或 `status≠ACTIVE`（含 REVOKED）→ 提示重新配对。
   - 由自定义 `AuthenticationEntryPoint` 按异常类型渲染统一 `ApiResponse` 信封（filter 抛异常，别在 filter 里手拼 JSON）。
6. **配对端点边界**：Chain 2 接管 `/cli/**` 后，`/cli/device-pairings`、`/cli/device-pairings/token` 保持 `permitAll`（"换令牌不能要令牌"的鸡蛋问题），其余 `/cli/**` `authenticated`。
7. **试金石端点**：新增 `GET /api/v1/cli/whoami` 返回当前认证 owner —— Chain 2 首个消费者 + 能写测试 + 背 L12 的 `auth status`（一石二鸟）。

## 备选与权衡
- **OAuth2 Resource Server + OpaqueTokenIntrospector**：标准化但需硬套抽象、引入用不上的机制 → 弃。
- **principal 只放 ownerUserId**：更简 YAGNI → 弃，因 Chain3/审计明确需 installation。
- **统一 401 不分过期/无效**：更简、不碰错误码冻结面 → 弃，因 CLI 需知道该 refresh 还是重配对，且正好接 L13 refresh。

## 后果
- ⚠️ **冻结面**：新增 2 错误码 `OWNER_TOKEN_EXPIRED` / `OWNER_TOKEN_INVALID` 属**错误码冻结面**，须在 Master Pack `16-codex/DRIFT-REGISTER.md` 登记。**Pack 不在本工作目录**——待主人给 Pack 路径后登记；先在仓库 `CHANGELOG.md` 留痕（[[changelog-ironlaw]]）。
- 新增未受 CSRF 保护的 Bearer 链，但 STATELESS + 不读 Cookie → 无 CSRF 面，安全无损。
- `/cli/**` 语义修正：从"Session 保护（实际 CLI 永远 401）"→"Bearer 保护"。
