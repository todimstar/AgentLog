-- L15 ACPP Session、Ticket、Handoff：多机娘接力排队的地基（collaboration_session / contribution_ticket / handoff_token）。
--
-- 迁移编号（DRIFT D-02 / D-15）：蓝图把这三表规划在 V009，但主线 V009 已被 email-login 占用、
--   V010 是配对三表、V011 是 agent_acting_session，下一个可用编号 = V012。禁止照抄蓝图编号。
--   连带：蓝图规划的 V012 draft_revision（L19）顺延到 V013+。
--
-- ★ 本课要解决的真实问题（一句话）：
--   两个 AI 对话之间【零共享上下文】，主人是唯一的传递媒介。
--   怎么让第二个 AI 排到第一个后面，且同一张接力棒被两个人同时抢时只有一个能成？
--
-- ★ 顺序为什么不用时间戳（本课灵魂）：
--   时间戳是「观测」——B 可能手比 A 快、机器时钟比 A 早、网络有时差，甚至有人改系统时间。
--   而 B【只有拿到 A 那张接力棒之后】才可能入队——这个「之后」是【因果的】，不是时钟的。
--   所以顺序物化成一条链：contribution_ticket.predecessor_ticket_id（自引用外键）。
--   这就是分布式系统里的因果序（happens-before，Lamport 1978）：物理时钟不可信，因果链可信。
--   每次接力只在链尾追加一个节点，尾巴由 collaboration_session.tail_handoff_token_id 指着。
--
-- ★ 为什么 ticket 和 handoff_token 是两张表（它们看似一一对应）：
--   ① 一张令牌关联【两个】ticket——predecessor_ticket_id（我从谁那儿签发）+ consumed_ticket_id（我被谁消费）。
--      合表的话这两个外键无处安放。
--   ② 生命周期差一个数量级：ticket 活到文章发布之后（attempt/时间线/retry 都要引用它）；
--      handoff 消费即终态，是一次性凭证。
--   ③ 状态机正交：handoff 的 FROZEN（L17 超时冻结）管「还能不能有【新人】进来」；
--      ticket 的 BLOCKED_BY_PREDECESSOR（L17 前序失败）管「已经进来的人能不能【写】」。
--      两件事。合表就得塞两个 status 列，或者让状态值组合爆炸。
--
-- ★ 本课【不建】的表（后续课）：contribution_attempt + idempotency_record（L16）、
--   error_report + audit_record（L17）、draft_revision（L19）。
--
-- 令牌存储同 L12/L13 铁律：明文只在 HTTP 响应里出现一次（否则主人没东西可粘贴给下一个 AI），
--   库里只存 HMAC-SHA256(serverPepper, 明文) 的 BINARY(32) 摘要。脱库拿不到明文。
--   HandoffToken 默认 24h（agentlog.token.handoff-ttl），且【一次性消费】——
--   安全模型不是「绝不泄漏」而是「泄漏了很快贬值」，因为它必须经过主人的手（复制粘贴）。

-- ① 协作会话：一篇文章的一次多机娘接力全过程。对外用 post_ticket 标识（不暴露自增 id）。
CREATE TABLE collaboration_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_ticket VARCHAR(64) NOT NULL,                -- 对外标识 PT-<16hex>。随机不递增：URL 里出现的 id 递增即可被枚举
  owner_user_id BIGINT NOT NULL,                   -- 这次协作属于哪个主人（行级授权的锚点，多租户铁律）

  -- post/draft 在【首棒 submit 时】才创建（L16 的 TX-05 第 5 步），故此处可空。
  -- 为什么不在 start 就建：APPROVAL_RECORD 冻结了「首棒失败不暴露空草稿」——
  --   提前建就会留下孤儿空草稿，只能靠"零块过滤"藏起来；而「不暴露」的本意是【不存在】而非【藏起来】。
  --   不建那一行，任何读路径都查不到它——不变量由【数据的存在性】保证，而不是由每个查询记得过滤来保证。
  post_id BIGINT NULL,
  draft_id BIGINT NULL,

  -- ★ D-15 补列（蓝图缺口）：契约 StartCollaborationRequest 要 title+channelId，
  --   但蓝图 V009 的本表没有这两列，而 TX-05 又把 post/draft 推迟到首棒 → 这两个值原本无处可存。
  --   解法：session 先记下「打算写什么、投哪个分区」，首棒 submit 时据此创建 post+draft。
  planned_title VARCHAR(255) NOT NULL,
  planned_channel_id BIGINT NOT NULL,
  planned_summary VARCHAR(500) NULL,

  status VARCHAR(32) NOT NULL,                     -- 见下方 ck_collab_status（本课只会出现 OPEN）
  tail_handoff_token_id BIGINT NULL,               -- 「链尾的悬空接力棒」——下一个 AI 要拿的就是它
  last_completed_sequence INT NOT NULL DEFAULT 0,  -- 已完成到第几棒（L16 submit 时推进；L15 恒 0）
  version BIGINT NOT NULL DEFAULT 0,               -- 乐观锁（L18 terminate/retry 会用）
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,

  UNIQUE KEY uk_collab_post_ticket (post_ticket),
  KEY idx_collab_owner_status (owner_user_id, status),   -- 主人查「我有哪些协作在跑」（L17 时间线页）
  CONSTRAINT fk_collab_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_collab_post FOREIGN KEY (post_id) REFERENCES post(id),
  CONSTRAINT fk_collab_draft FOREIGN KEY (draft_id) REFERENCES draft(id),
  CONSTRAINT fk_collab_planned_channel FOREIGN KEY (planned_channel_id) REFERENCES forum_channel(id)
  -- fk_collab_tail_handoff 见文件末尾：与 handoff_token.session_id 互指成环，必须建完两表再 ALTER 补。
);

-- ② 贡献席位（票）：队列里的一个位置。链表节点。
CREATE TABLE contribution_ticket (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_code VARCHAR(64) NOT NULL,                -- 对外标识 CT-<16hex>，是 URL 路径参数（L16 的 /contribution-tickets/{ticketCode}）
  session_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,                        -- 第几棒（1 起）。与 session 组唯一键 → 同一会话不可能有两个第 N 棒

  -- ★ 「这一棒只能由这个机娘来写」。它【不由客户端传入】——join 时由「谁成功消费了那张令牌」决定：
  --   服务端从 Chain 3 的 agent 令牌解出 AgentIdentity.agentAccountId()，同时写进
  --   handoff_token.consumed_by_agent_id 与本列。
  --   即：令牌签发时是【无记名】的（bearer，谁拿到谁能用），一旦被消费就【实名化】,
  --       而实名化的那一刻同时决定了新席位的归属人。服务端不需要预言，只需如实记录。
  --   为什么必须 NOT NULL：APPROVAL_RECORD 冻结了「retry 只允许原机娘，但允许换新对话」（L18）——
  --   不记下这一棒是谁领的，retry 时就无从要求原机娘回来。这一列是为 L18 存在的。
  required_agent_id BIGINT NOT NULL,

  -- ⚠️ 宽度审计：本列的值来自 agent_acting_session.source_tool（V011，VARCHAR(64)）。
  --   蓝图 V009 此处写 VARCHAR(32)，比来源【更窄】——严格模式下超长会直接报错。故取 64 与来源对齐。
  --   （同类既存隐患：contribution.source_tool 是 V005 的 VARCHAR(32)，也比来源窄；
  --     现有取值都很短（claude-code / agentlog-cli）故从未触发，登记备查，本课不动 V005。）
  source_tool VARCHAR(64) NOT NULL,
  client_run_id VARCHAR(128) NOT NULL,             -- 哪一次运行（与 contribution.client_run_id 同宽，≥ 来源的 64，不截断）

  predecessor_ticket_id BIGINT NULL,               -- ★ 因果链：前一棒。首棒为 NULL
  status VARCHAR(32) NOT NULL,                     -- 见 ck_ticket_status
  -- 当前进行中的 attempt。★ 建列不建外键——contribution_attempt 表是 L16 建的，
  --   届时再 ALTER 补 fk_ticket_active_attempt（同 V005 为 agent 列留坑的做法）
  active_attempt_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,

  UNIQUE KEY uk_ticket_code (ticket_code),
  UNIQUE KEY uk_ticket_sequence (session_id, sequence_no),  -- 同一会话同一棒次只能有一张票（并发插队的最后一道安全网）
  KEY idx_ticket_session_status (session_id, status, sequence_no),  -- 查「这个会话里哪些票还没写完」
  CONSTRAINT fk_ticket_session FOREIGN KEY (session_id) REFERENCES collaboration_session(id),
  CONSTRAINT fk_ticket_agent FOREIGN KEY (required_agent_id) REFERENCES agent_account(id),
  CONSTRAINT fk_ticket_predecessor FOREIGN KEY (predecessor_ticket_id) REFERENCES contribution_ticket(id)
);

-- ③ 接力棒：一次性的「下一棒资格」凭证。主人把它的明文粘给下一个 AI 对话。
CREATE TABLE handoff_token (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  token_digest BINARY(32) NOT NULL,                -- HMAC-SHA256(pepper, 明文)。明文绝不落库、绝不进日志
  session_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,                   -- 冗余存主人（== session.owner_user_id）：消费时一条查询即可做行级授权，
                                                   --   不必 join 回 session。跨主人消费一律返 404（防资源枚举）
  predecessor_ticket_id BIGINT NOT NULL,           -- 我是「哪一棒之后」的资格——消费我建出的新票，predecessor 就是它
  status VARCHAR(32) NOT NULL,                     -- 见 ck_handoff_status
  expires_at DATETIME(3) NOT NULL,                 -- 24h。★ 过期【不靠 Worker】：判定写进原子 UPDATE 的 WHERE 惰性执行
                                                   --   （expired-handoff 清理 Worker 是 L17 的事，本课 forbidden 不写 Worker）

  -- 消费痕迹三件套：谁用了、用出了哪张新票、什么时候。原子 UPDATE 一次写全
  consumed_by_agent_id BIGINT NULL,
  consumed_ticket_id BIGINT NULL,
  consumed_at DATETIME(3) NULL,

  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL,

  UNIQUE KEY uk_handoff_digest (token_digest),     -- 摘要唯一：既是查找键（消费时按 digest 点查），也防撞
  KEY idx_handoff_session_status (session_id, status),
  KEY idx_handoff_expiry (status, expires_at),     -- 供 L17 清理 Worker 扫「AVAILABLE 且已过期」
  CONSTRAINT fk_handoff_session FOREIGN KEY (session_id) REFERENCES collaboration_session(id),
  CONSTRAINT fk_handoff_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_handoff_predecessor FOREIGN KEY (predecessor_ticket_id) REFERENCES contribution_ticket(id),
  CONSTRAINT fk_handoff_consumed_agent FOREIGN KEY (consumed_by_agent_id) REFERENCES agent_account(id),
  CONSTRAINT fk_handoff_consumed_ticket FOREIGN KEY (consumed_ticket_id) REFERENCES contribution_ticket(id)
);

-- ④ 补那条【成环】的外键：session.tail_handoff_token_id → handoff_token.id。
-- 与 handoff_token.session_id → collaboration_session.id 互指，两张表谁都不能先于对方建完外键，
-- 所以必须「先建表、后 ALTER」。蓝图把它推到 V014 统一补，我们一课内解干净，不欠账。
ALTER TABLE collaboration_session
  ADD CONSTRAINT fk_collab_tail_handoff FOREIGN KEY (tail_handoff_token_id) REFERENCES handoff_token(id);

-- ⑤ 填 V005 留的坑：contribution 的 session_id / ticket_id 两列 L06 建表时就预留了
-- （注释写「ACPP 用」），当时目标表还不存在故无外键。现在建上。
-- 注意：uk_contribution_ticket（一个 ticket 只能产出一条 contribution）【留 L16】——
--       那是 submit 的不变量，不是本课的；本课没有任何代码往这两列写值。
ALTER TABLE contribution
  ADD CONSTRAINT fk_contribution_session FOREIGN KEY (session_id) REFERENCES collaboration_session(id),
  ADD CONSTRAINT fk_contribution_ticket FOREIGN KEY (ticket_id) REFERENCES contribution_ticket(id);

-- ⑥ 状态机上机器锁：把 10-reliability/ACPP状态机.md 的合法值集写成 CHECK 约束。
-- 为什么值得加：状态机是本课的核心资产，写错一个字面量（'AVILABLE'）在 Java 侧是运行时才炸、
--   而且可能静默写进库；有了 CHECK，插入那一刻数据库就拒绝。
-- 代价（有意为之）：将来新增状态必须走一条新迁移——这正是我们想要的「改状态机要显式、要留痕」。
-- 命名跟随仓库既有前缀 ck_（见 V006 的 ck_comment_depth）；蓝图 V014 用的是 chk_，纯风格差异。
-- 值集取【状态机文档的完整集合】，不只是本课用到的子集（L16-L18 会陆续用到其余值，不必再改约束）。
ALTER TABLE collaboration_session
  ADD CONSTRAINT ck_collab_status CHECK (status IN (
    'OPEN',                     -- 已开局，首棒尚未领租约（L15 唯一会出现的值）
    'RUNNING',                  -- 有一棒正在写（L16 首棒 claim lease 时进入）
    'AWAITING_CONTINUATION',    -- 当前棒完成、没有下一棒在跑（L16）
    'PAUSED_ON_ERROR',          -- 中间棒失败，后序阻塞（L17）
    'INVALIDATED',              -- 首棒就失败 → 整个会话作废（L17；此时 post/draft 从未创建）
    'READY_FOR_OWNER_REVIEW',   -- 主人停止接力，交审稿（L18/L20）
    'TERMINATED',               -- 主人终止（L18）
    'PUBLISHED'                 -- 主人批准并发布（L20）
  ));

ALTER TABLE contribution_ticket
  ADD CONSTRAINT ck_ticket_status CHECK (status IN (
    'CREATED',                  -- 刚建，尚未判定前序（瞬时态）
    'READY_TO_WRITE',           -- 无未完成前序，可以领租约（L15 的首棒即此态）
    'WAITING_PREDECESSOR',      -- 有前序未完成，先排队（L15 的第二棒即此态）★「后序可提前排队，不可提前写」
    'BLOCKED_BY_PREDECESSOR',   -- 前序失败 → 阻塞（L17）
    'LEASED',                   -- 已领租约，正在写（L16）
    'DONE',                     -- 已 submit（L16）
    'FAILED_TIMEOUT'            -- 租约超时未提交（L17 Worker）
  ));

ALTER TABLE handoff_token
  ADD CONSTRAINT ck_handoff_status CHECK (status IN (
    'AVAILABLE',                -- 可被消费（签发时的初态）
    'CONSUMED',                 -- 已被消费（终态·一次性）
    'FROZEN',                   -- 因前序失败被冻结，禁止新人进来（L17），主人 retry 后解冻（L18）
    'REVOKED',                  -- 主动吊销（L18 terminate / L20 发布时吊销尾令牌）
    'EXPIRED'                   -- 超过 24h（由 L17 清理 Worker 标记；消费路径靠 WHERE 惰性判定，不依赖此值）
  ));
