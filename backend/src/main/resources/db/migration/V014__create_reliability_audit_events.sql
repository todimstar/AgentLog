-- L17 Worker、Audit、Modulith 与 Redis：一棒死了之后秩序怎么自愈。
--
-- 迁移编号（DRIFT D-17）：Pack 把这三表规划在 V011，但主线 V011 已被
--   agent_acting_session 占用，下一个可用编号 = V014。续禁止照抄蓝图编号的铁律。
--
-- ★ 本课要解决的真实问题（一句话）：
--   L16 建立了秩序，但没有人处理"秩序崩溃"的情况。
--   机娘A 领了第 2 棒的租约后对话崩了，15 分钟过去，attempt 仍挂 ACTIVE、
--   ticket 仍挂 LEASED——正确性不受影响（惰性判定挡住了重复提交），
--   但状态对人撒谎：时间线页显示"正在写"，后序机娘永远在等一个不会来的信号。
--   ★ 本课回答：谁去宣布它死了 / 通知后面的人别等了 / 留下可查的记录？
--
-- ★ 两张流水表和之前的状态表（ticket/attempt/session）有三处关键不同：
--   ① 只有 created_at，没有 updated_at / version   —— 历史不能改，只 INSERT 永不 UPDATE
--   ② 没有唯一键约束                               —— 同一件事可能发生两次（retry 后再失败一次）
--   ③ 大量列可空 + JSON 兜底                       —— 一张表要装各种形状的事件；
--      判断一列该不该是正式列的标准：「需要查的就提成列，只是看一眼的进 JSON」
--
-- ★ event_publication 表是 Spring Modulith 的事务性发件箱（Transactional Outbox）：
--   普通的 Spring 事件有个致命问题——监听器执行失败，事件就丢了。
--   Modulith 的做法：在【业务的同一个事务里】把事件写进这张表；
--   监听器成功 → 标记完成；失败 → 留着；重启后重投。
--   于是「业务成功了，派生行为（写 ErrorReport / AuditRecord）一定不会丢」。
--   ⚠️ 为什么我们自己建而不让 Modulith 自动建：
--     spring.modulith.events.jdbc.schema-initialization.enabled=true 会绕过 Flyway，
--     破「数据库结构的唯一真相源是 Flyway」铁律。Pack 10-reliability/modulith-events.md
--     明确要求配 enabled=false + 走 Flyway 迁移。

-- ① 事故报告：一次失败的完整现场（卡在哪 / 为什么 / 建议怎么办）。
--   注意这张表被 contribution_attempt.error_report_id 引用——那列是 V013 就建了、
--   但外键要等本表存在才能补（文件末尾补）。
CREATE TABLE error_report (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,           -- 多租户隔离锚点（= session.owner_user_id）
  session_id BIGINT NOT NULL,
  ticket_id BIGINT NOT NULL,
  attempt_id BIGINT NULL,                  -- 可空：创建时 attempt 尚未关联（极少见）
  agent_id BIGINT NULL,                    -- 可空：首棒前出错时机娘可能未定
  error_type VARCHAR(64) NOT NULL,         -- 机器可读的分类，如 LEASE_TIMEOUT / SUBMIT_FAILED
  failed_stage VARCHAR(64) NOT NULL,       -- 出了什么事，如 AWAITING_TURN / SUBMITTING
  summary VARCHAR(512) NOT NULL,           -- 人话摘要，时间线页展示用
  technical_detail TEXT NULL,              -- 技术细节（堆栈 / SQL），调试用；NULL = 无额外信息
  suggested_actions_json JSON NOT NULL,    -- ★ 给你的建议动作，如 ["RETRY_TICKET","TERMINATE"]
                                           --   CLI / Skill 的自愈逻辑读这里；时间线页展示"怎么办"
  created_at DATETIME(3) NOT NULL,         -- 没有 updated_at / version：历史不能改

  KEY idx_error_owner_created (owner_user_id, created_at),
  KEY idx_error_session (session_id),
  CONSTRAINT fk_error_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_error_session FOREIGN KEY (session_id) REFERENCES collaboration_session(id),
  CONSTRAINT fk_error_ticket FOREIGN KEY (ticket_id) REFERENCES contribution_ticket(id),
  CONSTRAINT fk_error_attempt FOREIGN KEY (attempt_id) REFERENCES contribution_attempt(id),
  CONSTRAINT fk_error_agent FOREIGN KEY (agent_id) REFERENCES agent_account(id)
);

-- ② 动作流水：谁、何时、做了什么（不是内容历史）。
--   内容历史 → post_version（已发布快照）/ draft_revision（草稿编辑史，L19）。
--   这里记的是「机娘#7 在 14:03 领了第 2 棒的租约」这类操作事实，
--   不含正文，不能被修改，是协作过程的完整时间线数据源。
CREATE TABLE audit_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  session_id BIGINT NULL,                  -- 大量列可空：不同类型的操作涉及的资源不同
  ticket_id BIGINT NULL,
  agent_id BIGINT NULL,
  action_type VARCHAR(64) NOT NULL,        -- 见下方 ck_audit_action_type（本课实装的子集）
  summary VARCHAR(512) NOT NULL,           -- 人话，时间线页展示
  detail_json JSON NULL,                   -- 动作细节；不需要查的进这里，需要查的提成正式列
  created_at DATETIME(3) NOT NULL,         -- 没有 updated_at / version

  KEY idx_audit_owner_created (owner_user_id, created_at),
  KEY idx_audit_session_created (session_id, created_at),   -- 时间线页按 session 拉全量
  CONSTRAINT fk_audit_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_audit_session FOREIGN KEY (session_id) REFERENCES collaboration_session(id),
  CONSTRAINT fk_audit_ticket FOREIGN KEY (ticket_id) REFERENCES contribution_ticket(id),
  CONSTRAINT fk_audit_agent FOREIGN KEY (agent_id) REFERENCES agent_account(id)
);

-- ③ Modulith 事务性发件箱（Spring Modulith 1.4.11 · MySQL 方言）。
--   列定义严格对齐 schema-mysql.sql，一旦 Modulith 升级需跟进（这就是要手建的代价）。
--   ★ completion_date 为 NULL = 未完成 / 待重投；有值 = 已完成（completion-mode=delete 时直接删行）
CREATE TABLE IF NOT EXISTS EVENT_PUBLICATION (
  ID               VARCHAR(36)   NOT NULL,
  LISTENER_ID      VARCHAR(512)  NOT NULL,
  EVENT_TYPE       VARCHAR(512)  NOT NULL,
  SERIALIZED_EVENT VARCHAR(4000) NOT NULL,
  PUBLICATION_DATE TIMESTAMP(6)  NOT NULL,
  COMPLETION_DATE  TIMESTAMP(6)  DEFAULT NULL NULL,
  PRIMARY KEY (ID),
  INDEX EVENT_PUBLICATION_BY_COMPLETION_DATE_IDX (COMPLETION_DATE)
);

-- ④ 补 V013 承诺的坑：contribution_attempt.error_report_id → error_report。
--   V013 建列时 error_report 还不存在，注释里写了「建列不建 FK，L17 建 error_report 后补」。
ALTER TABLE contribution_attempt
  ADD CONSTRAINT fk_attempt_error FOREIGN KEY (error_report_id) REFERENCES error_report(id);

-- ⑤ 状态机约束：合法的 action_type 值集（取本课实装的完整集合；后续课新增值要走新迁移）。
--   ★ 这是「改状态机要显式、要留痕」的一贯做法（从 V012 起的传统）。
ALTER TABLE audit_record
  ADD CONSTRAINT ck_audit_action_type CHECK (action_type IN (
    'COLLAB_STARTED',           -- collab start：主人开局
    'HANDOFF_CLAIMED',          -- collab join：机娘入队
    'LEASE_CLAIMED',            -- collab claim-turn：领租约
    'CONTRIBUTION_SUBMITTED',   -- collab submit：提交成功
    'ATTEMPT_EXPIRED',          -- Worker 宣布超时
    'TICKET_BLOCKED',           -- 前序失败，本棒阻塞
    'SESSION_PAUSED',           -- 中间棒失败，会话暂停
    'SESSION_INVALIDATED'       -- 首棒失败，会话作废
  ));
