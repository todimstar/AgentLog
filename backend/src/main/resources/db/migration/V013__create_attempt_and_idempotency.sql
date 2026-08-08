-- L16 Wait、Attempt 与 Lease：一棒怎么写、怎么不被抢、怎么不写两遍。
--
-- 迁移编号（DRIFT D-02 / D-09 / D-15 / D-16）：蓝图把这两表规划在 V010，但主线 V010 已被设备配对占用、
--   V011 是 agent_acting_session、V012 是 collaboration 三表，下一个可用编号 = V013。禁止照抄蓝图编号。
--
-- ★ 本课要解决的真实问题（一句话）：
--   L15 把队列建好了，但队列里的人还不能写字。
--   一张票排到了，怎么让它的持有者【独占地】写完、顺位交棒；
--   且独占权到期能自动失效（不靠任何后台任务），网络重发也不会写出两段正文？
--
-- ★ 为什么突然冒出「租约」这个东西（本课的起点）：
--   L14 的投稿、L15 的接力，都是【瞬时动作】——一个请求 200ms 做完，没有"进行中"这个状态。
--   L16 不一样：机娘先说「该我了」，然后去读上一棒、思考、生成几百字，【几分钟后】才回来提交。
--   于是第一次出现了「一段进行中的时间」，问题随之而来：这几分钟里，这个席位处于什么状态？
--     做法一 不管它     → 第二个机娘也能同时开写同一棒，两份内容撞车。排除。
--     做法二 加锁       → 锁需要有人来解。机娘的对话可能崩了、进程被 kill、终端被关掉，
--                        持有者【永远不会回来解锁】→ 这一棒永久卡死，整条接力链断在这里。
--     做法三 租约 ✅    → 给的不是锁，是【有到期时间的独占权】：15 分钟后自动失效，不需要任何人来解。
--   租约 = 对付「持有者可能永远不回来」的标准答案。锁靠人解，租约靠时钟自愈。
--
-- ★ 为什么 Lease 内嵌在 Attempt 里、而 Ticket 与 Attempt 却要分表（合与分的判据）：
--   判据两条：① 基数会不会破 1:1  ② 生命周期是否同生共死。两个都满足才合表。
--     ticket ↔ attempt：一张票可能被写好几次（超时后主人 retry，L18）——票不变、attempt 重开，
--                       基数 1:N，且票活到文章发布后（审计要读）而 attempt 短命。→ 【分表】
--     attempt ↔ lease ：一次尝试有且仅有一个租约，租约失效即这次尝试失败——
--                       恒 1:1 且同生共死。→ 【合表】（五个 lease_* 列直接内嵌）
--   ⚠️ 与 L15 的 ticket ↔ handoff 正好相反（那两者基数会破 1:1、状态机正交，故必须分表）。
--      同一个协议里给出「该合」与「该分」的两个反例，判据才立得住。
--
-- ★ 本课【不建】的表（后续课）：error_report + audit_record（L17）、draft_revision（L19）。

-- ① 贡献尝试：一个席位上的【一次写作过程】。租约五列内嵌于此。
CREATE TABLE contribution_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  ticket_id BIGINT NOT NULL,                       -- 哪个席位（contribution_ticket）
  attempt_no INT NOT NULL,                         -- 这张票的第几次尝试（1 起）。L16 恒为 1；retry(L18) 才有 2、3…
  status VARCHAR(32) NOT NULL,                     -- 见下方 ck_attempt_status

  -- ↓↓↓ 内嵌的租约（合表判据见文件头）↓↓↓
  -- 令牌存储同 L12/L13/L15 铁律：明文只在 claim lease 的响应里出现一次，库里只存 HMAC-SHA256 摘要。
  -- ⚠️ 与 handoff 令牌的关键差别：handoff 必须【经过主人的手】复制粘贴到另一个 AI 对话，所以要回显给人；
  --    lease【不经过人】——同一个 CLI 进程 claim-turn 领了、submit 时自己用，回显只增加泄漏面。
  --    故 07-cli/cli-spec.md 的输出规则明写「不输出 lease token」。
  --    同样是一次性令牌，可见性策略由【传递路径】决定，不由令牌类型决定。
  lease_token_digest BINARY(32) NULL,              -- HMAC-SHA256(pepper, 明文)
  lease_issued_at DATETIME(3) NULL,                -- 几点领的
  lease_expires_at DATETIME(3) NULL,               -- ★ 几点到期 —— 本课最重要的一列
                                                   --   过期判定写进 submit 那条 UPDATE 的 WHERE 做【惰性执行】：
                                                   --   即使超时的租约还挂着 ACTIVE（L17 的 Worker 还没来标记），
                                                   --   它也一定提交不进来。这就是本课能在「禁止写 Worker」
                                                   --   的约束下依然安全的全部原因。
  last_heartbeat_at DATETIME(3) NULL,              -- 心跳（本课【建列不用】，L17 续租用）
  max_lease_expires_at DATETIME(3) NULL,           -- 续租上限，防无限续租（本课【建列不用】，L18 用）

  error_report_id BIGINT NULL,                     -- 失败原因指针。★建列不建外键——error_report 表是 L17 建的，
                                                   --   届时再 ALTER 补（同 V012 为 active_attempt_id 留坑的做法）
  started_at DATETIME(3) NULL,                     -- 开始写（= 领租约那一刻）
  finished_at DATETIME(3) NULL,                    -- 结束（成功或失败）
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,

  -- 同一张票不可能有两个「第 N 次尝试」。★ 这是【最后一道安全网】，不是闸门——
  --   正常路径永不触发（闸门是 ClaimLeaseService 里那条带条件的 UPDATE）。
  --   若业务逻辑要靠「撞了这个键」来判断「我输了」，那就是【用异常控制业务流程】：
  --   它只挡得住"撞车"，挡不住"你本来就没资格上路"（票不是你的 / 状态不对 / 会话已终止）。
  UNIQUE KEY uk_attempt_ticket_no (ticket_id, attempt_no),
  -- 租约令牌摘要唯一：既防撞，也是 submit 时的【查找键】（拿令牌摘要点查这一行）。
  -- 可空列上的 UNIQUE 在 MySQL 里允许多行 NULL，故「尚未签发租约」的行不受约束。
  UNIQUE KEY uk_attempt_lease_digest (lease_token_digest),
  KEY idx_attempt_expiry (status, lease_expires_at),   -- 供 L17 Worker 扫「ACTIVE 且已超时」
  CONSTRAINT fk_attempt_ticket FOREIGN KEY (ticket_id) REFERENCES contribution_ticket(id)
);

-- ② 幂等台账：一张流水账，记「这个 key 我处理过没有、处理成什么样了」。
--
-- ★ 为什么需要它：CLI 提交时网络超时，客户端【无法判断】服务端收没收到。
--   不重发 → 可能真的丢了；重发 → 可能写出两段正文。幂等就是让"重发"变成安全动作。
--
-- ★ 唯一键就是判定器（与上面 uk_attempt_ticket_no 恰好相反的用法，判据见下）：
--   幂等要判定的【全部内容】就是「这个 key 来过没有」，而 uk_idempotency 守的正是这件事，
--   且【覆盖 100% 的失败情形】（没有第二种"来过"的方式）。所以直接 INSERT、撞键即判定，是正解。
--   而 claim lease 要判定的是「这张票能不能领」（状态 + 归属 + 时效），
--   uk_attempt_ticket_no 守的却是「第 N 次尝试唯一」——只是【碰巧相关】，覆盖不全，所以不能拿它当闸门。
--   判据一句话：★ 那个唯一键守的不变量，是不是【就是】你此刻要判定的那件事，且覆盖全部失败情形？
--
-- ★ 与 L15 的条件 UPDATE 是同一思想的两种形态：
--     要改已有的行 → 条件 UPDATE，affectedRows 裁决
--     要建全新的行 → 抢占 INSERT，唯一键冲突裁决
--   共同点：让数据库在【一条语句内】完成"检查 + 动作"，中间不留任何缝隙（消灭 TOCTOU 窗口）。
CREATE TABLE idempotency_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,                   -- 幂等域按主人隔离（多租户铁律）
  agent_id BIGINT NOT NULL,                        -- 再按机娘隔离：两个机娘各自的 key 互不干扰
  -- ★ 存【路由模板】而不是实际 URI：实际 URI 含 ticketCode，会把同一个 key 在不同票上的使用
  --   切成两个幂等域，反而破坏幂等语义。路径参数改为参与 request_hash。
  endpoint VARCHAR(128) NOT NULL,                  -- 如 POST /api/v1/agent/contribution-tickets/{ticketCode}/leases
  idempotency_key VARCHAR(128) NOT NULL,           -- 客户端生成的随机串（Idempotency-Key 头）
  request_hash CHAR(64) NOT NULL,                  -- SHA-256(路径参数 + 请求体)。同 key 不同 hash → 409
  status VARCHAR(32) NOT NULL,                     -- 见 ck_idempotency_status
  response_status INT NULL,                        -- 完成时缓存的 HTTP 状态码
  response_body_json JSON NULL,                    -- 完成时缓存的响应体，重放时原样返回（业务一行都不执行）
  expires_at DATETIME(3) NOT NULL,                 -- 默认 24h。纯清理用途（L17 Worker），不参与任何判定
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,

  UNIQUE KEY uk_idempotency (owner_user_id, agent_id, endpoint, idempotency_key),
  KEY idx_idempotency_cleanup (expires_at),
  CONSTRAINT fk_idempotency_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_idempotency_agent FOREIGN KEY (agent_id) REFERENCES agent_account(id)
);

-- ③ 补 V012 留的坑之一：contribution_ticket.active_attempt_id 的外键。
-- V012 建列时目标表还不存在（注释写着「L16 建 attempt 表时再 ALTER 补」），现在兑现。
ALTER TABLE contribution_ticket
  ADD CONSTRAINT fk_ticket_active_attempt FOREIGN KEY (active_attempt_id) REFERENCES contribution_attempt(id);

-- ④ 补 V012 留的坑之二：一张票只能产出一条贡献（submit 的不变量）。
-- V012 的注释写着「uk_contribution_ticket 留 L16——那是 submit 的不变量，不是本课的」，现在兑现。
-- ticket_id 可空（主人直接投稿的 contribution 不属于任何协作），MySQL 的 UNIQUE 允许多行 NULL，不受影响。
-- 定位同 ③：最后一道安全网，正常路径由幂等 + Attempt 闸门挡住，永不触发。
ALTER TABLE contribution
  ADD CONSTRAINT uk_contribution_ticket UNIQUE (ticket_id);

-- ⑤ ★ 设计修正（DRIFT D-16 · 主人 2026-08-02 提出并说服我）：handoff 令牌【不该有 TTL】。
--
--   原设计：handoff_token.expires_at NOT NULL，默认 24 小时。
--   主人的论证：「能不能再来人」这件事没有【时间】维度的需求，只有【生命周期】维度的需求——
--     论坛文章只要还在，就永远有被续写的可能（人都可以修改去年的文章）；
--     反倒「很久之后仍来人」是真实需求，24h TTL 会误伤它。
--   蓝图自己站在主人这边：transaction-boundaries.md 的 TX-02（主人发布）第 11 步
--     已经写着「吊销尾部 HandoffToken」——【生命周期驱动的吊销本来就有】。
--     TTL 是回答同一个问题的第二套机制，而且答得更差（会误伤正常用户）。两套机制留一套。
--
--   ★ 判据（同一课里 lease 必须有 TTL、handoff 不该有，值得记住）：
--       Lease 管【独占】——持有者不回来，队列永久卡死，只能靠时钟终结 → 必须有 TTL
--       Handoff 管【资格】——持有者不回来，什么也不会发生，令牌就躺在那儿 → 不必有 TTL
--     一句话：★ 独占必须有期限，资格不必有期限。因为独占会挡住别人，资格不挡任何人。
--
--   改法（机制保留、策略默认关闭）：列改可空，NULL = 永不过期；
--   agentlog.token.handoff-ttl 配成空即不签发过期时刻。消费 SQL 改为 (expires_at IS NULL OR expires_at >= #{now})。
--   ACPP_HANDOFF_EXPIRED(410)、状态机的 EXPIRED、L17 的清理 Worker【全部保留】——
--   将来公开部署或有合规要求时改一行配置即可开启。默认关闭是产品判断，不是能力缺失。
ALTER TABLE handoff_token
  MODIFY COLUMN expires_at DATETIME(3) NULL;

-- ⑥ 状态机上机器锁（延续 V012 的做法：把 10-reliability/ACPP状态机.md 的合法值集写成 CHECK）。
-- 值集取【状态机文档的完整集合】而非本课用到的子集，免得后续课再加迁移。
--
-- ⚠️ READY 这个值【当前无产生路径】（DRIFT D-16）：状态机文档写 READY -> ACTIVE，
--    但 TX-04 第 4-5 步是「创建 attempt 时就写入租约」——创建即 ACTIVE，READY 没有任何代码能产生。
--    保留在值集里，是为 L18 retry 预留（届时可能出现"已排定但尚未领租约"的中间态）。
ALTER TABLE contribution_attempt
  ADD CONSTRAINT ck_attempt_status CHECK (status IN (
    'READY',            -- 【本课无产生路径】保留给 L18
    'ACTIVE',           -- 租约在手，正在写（L16 claim lease 的初态）
    'SUCCEEDED',        -- submit 成功（L16）
    'FAILED_TIMEOUT',   -- 租约超时未提交（L17 Worker 标记）
    'FAILED_CLIENT',    -- 机娘自己报告失败（L18）
    'REVOKED'           -- 主人终止协作（L18）
  ));

ALTER TABLE idempotency_record
  ADD CONSTRAINT ck_idempotency_status CHECK (status IN (
    'IN_PROGRESS',      -- 已抢占、业务执行中。★ 这个状态必须对并发者【立刻可见】，
                        --   所以台账写入走 REQUIRES_NEW 独立事务——藏在未提交的事务里等于不存在。
    'COMPLETED'         -- 业务成功，响应已缓存。重放时直接返回，业务一行都不执行
  ));
