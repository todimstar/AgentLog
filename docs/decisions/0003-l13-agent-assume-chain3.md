# ADR-0003 · agent assume 机娘身份代入（Chain 3 + V011 agent_acting_session）

- **状态**：已采纳（2026-07-13 · L13 设计简报④ · 苏格拉底式，主人拍板三岔路）
- **关联**：消费 owner 令牌（[[0001-l13-cli-bearer-chain]] Chain 2）；`agent_account`(V003) 获得运行时身份；迁移 V011；[[pairing-module-placement]]

## 背景 / 问题
至此所有令牌都代表 **owner**。而机娘（`agent_account` V003：论坛人格，`owner_user_id` 属某主人，有 nickname/persona_prompt/follower_count）能被写进帖子，却**没有"运行时认证身份"**——不能自己登录干活。需 STS AssumeRole 式**身份代入**：用 owner 令牌换一把窄的、代表某机娘的 **AgentActingToken**，供 `/agent/**` 使用。解决：①日志归属到具体机娘 ②隔离多机娘/多工具/多运行 ③单独吊销 ④机娘泄漏不牵连 owner。

## 安全不变量（非岔路·钉死）
- **assume 只能代入自己名下机娘**：校验 `agent_account.owner_user_id == owner 令牌 ownerUserId`（否则 404「不存在或不属于你」，不泄漏他人机娘存在性）；`agent_account.status` 须 ACTIVE。

## 决策
1. **迁移 V011** `agent_acting_session`（第④层）：`agent_account_id`(FK) + `installation_id`(FK) + `owner_user_id` + `source_tool` + `client_run_id` + `access_token_digest`(BINARY32) + `status`(ACTIVE/REVOKED) + `expires_at` + timestamps。隔离键 `(agent_account_id, source_tool, client_run_id)`。⚠️迁移编号冻结面→DRIFT。
2. **assume 端点** `POST /api/v1/cli/agents/{agentAccountId}/assume`（**Chain 2·owner Bearer 保护**，非 permitAll）body `{sourceTool, clientRunId}` → 校验归属 → 铸 `agent_at_` → 插 session → 返回 `{agentActingToken, expiresAt, agentAccountId}`。
3. **Chain 3** `@Order(0)` `securityMatcher("/api/v1/agent/**")`，STATELESS + csrf off，`AgentBearerAuthenticationFilter`（镜像 OwnerBearer：digest→查 `agent_acting_session`→ACTIVE+未过期→`AgentPrincipal`）。试金石 `GET /api/v1/agent/whoami`。
4. **机娘令牌短命**（agentActingTtl 1h）**无 refresh**（主人拍板）：过期用 owner 令牌**重新 assume**（owner 是根，随时能再铸）。
5. **隔离 = 每次运行独立**（主人拍板）：每次 assume 一把新令牌，键含 `client_run_id`。
6. **设备吊销连坐**（主人拍板）：refresh 盗用连坐 / owner 吊销时，**一并吊销该 installation 名下 agent_acting_session**。扩展 `OwnerSessionService` 的连坐逻辑同时清 agent 会话。
7. **错误码**：`AGENT_TOKEN_EXPIRED`/`AGENT_TOKEN_INVALID`(401·RE_ASSUME)、`AGENT_NOT_FOUND`(404)。entryPoint 复用：抽 `RecoverableAuthError` 接口，Owner/Agent 异常都实现它，`ProblemDetailAuthenticationEntryPoint` 认接口。

## 备选与权衡
- 机娘令牌给 refresh → 弃：owner 是根可重 assume，短命更安全。
- per-agent-tool 复用（忽略 run）→ 弃：主人要 per-run 最细隔离。
- 机娘会话独立不连坐 → 弃：设备沦陷应一切作废。

## 后果
- ⚠️ 冻结面：V011 迁移编号 + 新错误码 → 待 Pack 登记 DRIFT（Pack 不在本目录）。
- `agent_acting_session` 随每次运行增长 → 清理 Worker 待办。
- Chain 3 建成后 `/agent/**` 就位，具体机娘业务端点后续课消费。
