-- L13 机娘身份代入（agent assume）：agent_acting_session —— 四层模型第④层（ADR-0003）。
--
-- 迁移编号（DRIFT）：赶工蓝图曾把此表放 V009，但主线 V009 已被 email-login 占用、V010 是配对三表，
--   下一个可用编号是 V011。禁止照抄蓝图编号，否则 Flyway 撞车。
--
-- 机娘（agent_account V003：论坛人格，owner_user_id 属主人，有 nickname/persona_prompt/follower_count）
--   在此获得「运行时认证身份」：owner 持 owner 令牌，POST /cli/agents/{id}/assume「代入」自己名下的机娘，
--   换一把窄的 AgentActingToken（Chain 3 /agent/**）。STS AssumeRole 式权限代入：
--   日志归属到具体机娘、多机娘隔离、单独吊销、机娘泄漏不牵连 owner。
--
-- 令牌摘要同 owner：明文只回一次，库存 HMAC-SHA256(pepper) 的 BINARY(32) 摘要（脱库不可逆推）。

CREATE TABLE agent_acting_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  agent_account_id BIGINT NOT NULL,                -- 代入哪个机娘人格（FK agent_account）
  installation_id BIGINT NOT NULL,                 -- 在哪台设备上运行（FK client_installation）
  owner_user_id BIGINT NOT NULL,                   -- 机娘的主人（冗余存，快速鉴权/审计；== agent_account.owner_user_id）
  source_tool VARCHAR(64) NOT NULL,                -- 哪个工具/CLI/skill 在跑（claude-code / cursor…）
  client_run_id VARCHAR(64) NOT NULL,              -- 哪一次运行（「每次运行独立」隔离的最细粒度）
  access_token_digest BINARY(32) NOT NULL,         -- AgentActingToken 摘要（默认 1h·短命无 refresh）
  status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',    -- ACTIVE / REVOKED（设备吊销时连坐）
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_agent_acting_digest (access_token_digest),
  KEY idx_agent_acting_isolation (agent_account_id, source_tool, client_run_id),  -- 隔离键（agent+tool+run）
  KEY idx_agent_acting_installation (installation_id, status),                     -- 设备吊销连坐扫描
  CONSTRAINT fk_agent_acting_agent FOREIGN KEY (agent_account_id) REFERENCES agent_account(id),
  CONSTRAINT fk_agent_acting_installation FOREIGN KEY (installation_id) REFERENCES client_installation(id),
  CONSTRAINT fk_agent_acting_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id)
);
