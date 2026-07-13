# ADR-0002 · auth refresh 令牌轮换（RTR + 盗用连坐吊销）

- **状态**：已采纳（2026-07-13 · L13 设计简报③ · 苏格拉底式，主人拍板两岔路）
- **关联**：消费 [[0001-l13-cli-bearer-chain]] 埋的 `OWNER_TOKEN_EXPIRED → REFRESH_TOKEN`；表 `owner_access_session`（V010，**无新迁移**）

## 背景 / 问题
OwnerAccessToken TTL 仅 1h，过期即 `OWNER_TOKEN_EXPIRED`。不能每小时让主人重新扫码配对。CLI 持 30d refresh token 换新 access。`owner_access_session` L12 已备 `refresh_token_digest` / `refresh_expires_at` / `status`。

## 决策
1. **端点** `POST /api/v1/cli/auth/refresh { refreshToken }`，`permitAll`（access 已过期走不了 Bearer；refresh token 自证，与配对端点并列）。
2. **全轮换 RTR**（主人拍板）：每次 refresh 发**新 access + 新 refresh**，旧会话 `status=REVOKED`（L12 `OwnerSessionStatus` 那句"刷新轮换旧会话"预留）。
3. **表动作 = 新 row + 旧 row 置 REVOKED**（新 access/refresh 摘要、新有效期、ACTIVE、同 installation/owner）。无需 V011。**副作用**：旧 access token 因其 row 变 REVOKED 而**立即失效**（Chain 2 → `OWNER_TOKEN_INVALID`）——一次 refresh 把旧 access+refresh 一起作废。
4. **盗用连坐吊销**（主人拍板）：查到的 session `status=REVOKED`（= 已轮换的 refresh 被重放）→ 疑似盗用 → 吊销该 `installation_id` 名下**所有 ACTIVE 会话**，强制重新配对。现有 `installation_id` 即可实现（合 OAuth 2.0 Security BCP 的 RTR 重放响应）。
5. **失败语义**（新错误码·均 401）：`REFRESH_TOKEN_INVALID`（查无/已轮换/吊销 → 重新配对）、`REFRESH_TOKEN_EXPIRED`（refresh 30d 到期 → 重新配对）。经 `GlobalExceptionHandler` 出信封（refresh 是普通 Controller，非 filter，不需 entryPoint）。
6. **服务** = 新 `OwnerSessionService`（会话生命周期，不塞进只管配对的 `DevicePairingService`）。

## 备选与权衡
- **只换 access（refresh 不变）**：更简 → 弃，refresh 泄漏 30d 敞口且不可检测。
- **简单失败（重放不连坐）**：更简 → 弃，放弃 RTR 的核心价值（盗用检测）。
- **独立 refresh 会话表**：过度设计 → 弃，单表新 row 足够。

## 后果
- ⚠️ **冻结面**：新增 `REFRESH_TOKEN_EXPIRED` / `REFRESH_TOKEN_INVALID` → 错误码冻结面，待接入 Pack 登记 DRIFT（同 ADR-0001）。
- `owner_access_session` 行数随轮换增长（每 refresh +1 行）→ 后续加"过期会话清理 Worker"（progress-pointer 待办）。
- 安全性：refresh 泄漏窗口缩短 + 重放即触发连坐吊销，显著优于长期静态 refresh token。
