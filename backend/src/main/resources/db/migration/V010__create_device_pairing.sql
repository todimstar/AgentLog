-- L12 CLI 与浏览器设备配对：client_installation + device_pairing_request + owner_access_session。
--
-- 迁移编号说明（DRIFT-REGISTER D-02）：设计蓝图把这些表规划在 V007，但主线 V006-V009 已被
-- comment/reaction/seed/email-login 占用，下一个可用编号是 V010。禁止照抄蓝图编号，否则 Flyway 撞车。
-- 本课只建「配对三表」；agent_acting_session（机娘代理令牌）留到 L13。
--
-- OAuth 2.0 设备授权流（Device Authorization Grant, RFC 8628）—— 无键盘/无浏览器的客户端如何借浏览器授权：
--   1) CLI 提交 installationCode → 建 client_installation + device_pairing_request，
--      返回 deviceCode（CLI 私藏、轮询用）+ userCode（短码、给人在浏览器输入批准）。
--   2) 主人浏览器登录后输入 userCode 批准 → pairing.status = CONFIRMED。
--   3) CLI 轮询 deviceCode → 已确认则签发 OwnerAccessToken + OwnerRefreshToken。
--
-- 安全基石：所有令牌/deviceCode 明文只回客户端一次，库里只存 HMAC-SHA256(serverPepper, 明文) 的 BINARY(32) 摘要。
--   脱库时攻击者拿到的只是摘要，没有服务端 pepper 算不出明文，无法冒用（与密码存 bcrypt 同理，可验证不可逆推）。

-- ① 客户端安装：一台设备（一次 CLI 安装）一条记录。配对确认后绑定到主人。
CREATE TABLE client_installation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  installation_code VARCHAR(64) NOT NULL,          -- CLI 首次运行生成的设备标识（本机持久化，多次配对复用同一条）
  owner_user_id BIGINT NULL,                        -- 配对确认后绑定到主人；未配对时为 NULL
  device_name VARCHAR(128) NOT NULL,               -- 设备名（如 "Windows 11-cli"），仅供主人辨认
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING',    -- PENDING（未配对）/ ACTIVE（已绑定主人）/ DISABLED（吊销）
  last_seen_at DATETIME(3) NULL,                    -- 最近活跃时间（L13+ 令牌校验时刷新）
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_installation_code (installation_code),
  KEY idx_installation_owner_status (owner_user_id, status),
  CONSTRAINT fk_installation_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id)
);

-- ② 配对请求：一次 auth login 一条。deviceCode 摘要 + 给人看的 userCode + 过期时间。
CREATE TABLE device_pairing_request (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  installation_id BIGINT NOT NULL,
  device_code_digest BINARY(32) NOT NULL,          -- deviceCode 的 HMAC 摘要（明文只回 CLI 一次，之后 CLI 凭它轮询）
  user_code VARCHAR(16) NOT NULL,                  -- 给人看的短码（浏览器里输），明文存（短期、低敏感、10 分钟即弃）
  status VARCHAR(32) NOT NULL,                      -- PENDING / CONFIRMED / CONSUMED / EXPIRED
  confirmed_by_user_id BIGINT NULL,                -- 哪个主人批准的（浏览器登录态派生，不由客户端传入）
  expires_at DATETIME(3) NOT NULL,                 -- 配对码 10 分钟过期（验收项「过期处理」的依据）
  confirmed_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pairing_digest (device_code_digest),
  UNIQUE KEY uk_pairing_user_code (user_code),      -- userCode 全局唯一，确保浏览器输入能唯一定位一条配对
  KEY idx_pairing_expiry (status, expires_at),      -- 供未来清理 Worker 扫「PENDING 且已过期」
  CONSTRAINT fk_pairing_installation FOREIGN KEY (installation_id) REFERENCES client_installation(id),
  CONSTRAINT fk_pairing_confirmer FOREIGN KEY (confirmed_by_user_id) REFERENCES user_account(id)
);

-- ③ 主人访问会话：配对成功后签发的 OwnerAccessToken + RefreshToken（本课选定的「Opaque + 令牌表」模型）。
--    一行 = 一个会话，同时存 access 与 refresh 两个摘要。L13 的 bearer 过滤器将查此表校验令牌。
CREATE TABLE owner_access_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  installation_id BIGINT NOT NULL,                 -- 令牌属于哪台设备
  owner_user_id BIGINT NOT NULL,                   -- 令牌代表哪个主人
  access_token_digest BINARY(32) NOT NULL,         -- OwnerAccessToken 摘要（默认 1h）
  refresh_token_digest BINARY(32) NOT NULL,        -- OwnerRefreshToken 摘要（默认 30d，L13 用它换新 access）
  status VARCHAR(32) NOT NULL,                      -- ACTIVE / REVOKED
  access_expires_at DATETIME(3) NOT NULL,
  refresh_expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_owner_access_digest (access_token_digest),
  UNIQUE KEY uk_owner_refresh_digest (refresh_token_digest),
  KEY idx_owner_session_installation (installation_id, status),
  CONSTRAINT fk_owner_session_installation FOREIGN KEY (installation_id) REFERENCES client_installation(id),
  CONSTRAINT fk_owner_session_user FOREIGN KEY (owner_user_id) REFERENCES user_account(id)
);
