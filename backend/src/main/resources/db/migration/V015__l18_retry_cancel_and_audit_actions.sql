-- L18 Retry 与 Terminate：冻住之后，谁来解冻。
--
-- 迁移编号（DRIFT D-02/D-09/D-15/D-16/D-17 连撞五课的坑）：仓库已到 V014，下一个可用 = V015。
--   蓝图没有为本课规划迁移（它以为 retry 只改状态不改结构），所以这次没有编号可抄，但铁律照旧：
--   ★ 已经应用过的迁移文件，一个字符都不能改——包括注释（Flyway checksum 按整个文件算，L16 血教训）。
--
-- ★ 本课要解决的真实问题（一句话）：
--   L17 让秩序在崩溃后自愈了，但自愈的结果是【冻住】——票 FAILED_TIMEOUT、后序整条尾巴 BLOCKED、
--   尾令牌 FROZEN、会话 PAUSED_ON_ERROR。L17 写的所有代码里，没有一行能让这个局面重新动起来。
--   而解冻【必须由人触发】：机娘的对话已经崩了，服务端与机娘之间是「拉」不是「推」的关系，
--   它连对方还在不在都不知道。自动 retry 只会把票改回可写、再超时、再重试 —— 死循环。
--   ⇒ 这是整个项目第一次出现「机器有能力做、但故意不让它做」的环节（human-in-the-loop）。
--
-- ★ 为什么本课【只加状态值、不加表】：
--   retry 不是「新建一次尝试」，而是「把票改回可写」。新的 attempt 由机娘 claim-turn 时
--   照旧走 selectMaxAttemptNo+1 自然长出来（attempt_no=2）。
--   ——蓝图原本设想 retry 时就建出 attempt(status='READY')，我们否决了：那样会长出
--     「名额发了、原机娘再也不回来」的僵尸行，而蓝图的 5 个 Worker（worker-parameters.yaml）
--     里【没有一个】会扫 READY 的 attempt（L17 那个扫的是 status='ACTIVE' AND lease_expires_at < now，
--     READY 行没有 lease_expires_at，永远捞不到）。
--   ⇒ 判据：不建那一行，就不可能有僵尸。同 L15「首棒失败不暴露空草稿」——
--     不变量由【数据的存在性】保证，而不是由每个查询记得过滤来保证。
--   ⇒ 连带结论：contribution_attempt 的 'READY' 值【永久无产生路径】（D-16 曾猜它留给 L18，猜错了）。
--     值集里留着不删——删它要写迁移，而留着不产生它没有任何成本。

-- ① 票的终态：CANCELLED —— 主人收工时，那些还在排队的席位。
--
-- ★ 为什么必须新增这个值，而不能「靠 session 的终态兜底」（本课辩论出来的）：
--   理由不是「状态机好看」，是【让机娘停下来】。
--
--   收工后若不改票状态，第 3 棒的机娘正跑着 collab wait：
--     它查 TicketStatusService → 那个服务【只读票】算 pollAfterSeconds
--     → 票还是 WAITING_PREDECESSOR → 答「5 秒后再来问」
--     → 它一直问到 900 秒超时退出。
--   服务端每一次都在【说真话，但骗了它】：「前一棒尚未完成」是事实，
--   真相却是「这条协作已经收工了，你可以走了」。
--
--   要让 session 兜底，就得让【每一个读票的地方】都去 join session ——
--   而每写一个新查询都可能忘记。同 L15 判据：不变量靠数据本身，不靠每个查询记得过滤。
--
-- ★ 为什么叫 CANCELLED 而不是 REVOKED（handoff/attempt 用的那个词）：
--   REVOKED = 吊销一张【凭证】（令牌、租约），语义是「这东西作废了」；
--   CANCELLED = 取消一个【席位】，语义是「这个位置不再需要有人来坐」。
--   票不是凭证，它是队列里的位置。词要跟着语义走。
ALTER TABLE contribution_ticket DROP CHECK ck_ticket_status;
ALTER TABLE contribution_ticket
  ADD CONSTRAINT ck_ticket_status CHECK (status IN (
    'CREATED',                  -- 【至今无产生路径】蓝图说是瞬时态，实装直接跳过
    'READY_TO_WRITE',           -- 可以领租约（L15 首棒 / L16 唤醒后继 / ★L18 retry）
    'WAITING_PREDECESSOR',      -- 有前序未完成，排队中（L15）
    'BLOCKED_BY_PREDECESSOR',   -- 前序失败被阻塞（L17，递归 CTE 冻整条尾巴）
    'LEASED',                   -- 已领租约，正在写（L16）
    'DONE',                     -- 已 submit（L16）
    'FAILED_TIMEOUT',           -- 租约超时未提交（L17 Worker）
    'CANCELLED'                 -- ★ L18 新增：主人收工，这个席位不再需要了
  ));

-- ② 审计动作 +5。
--
-- ★ 本课【补齐了三个从 V014 起就列在值集里、却从来没有任何代码写过】的动作：
--   COLLAB_STARTED / HANDOFF_CLAIMED / LEASE_CLAIMED —— 它们本课才真正被写入。
--   后果曾是：时间线只有「提交」和「超时」两种事件，页面会显示一条协作凭空从
--   「第 1 棒提交成功」开始，前面谁开的局、谁接的棒、谁领的租约全是空白。
--   ⇒ 教训：CHECK 值集里有个值，不等于有代码会产生它。
--     【值集是承诺，不是实现】——而没有任何机器能检查「承诺有没有兑现」。
ALTER TABLE audit_record DROP CHECK ck_audit_action_type;
ALTER TABLE audit_record
  ADD CONSTRAINT ck_audit_action_type CHECK (action_type IN (
    -- ↓ V014 定义的 8 个（其中前三个本课才第一次真正被写入）
    'COLLAB_STARTED',           -- collab start：主人开局
    'HANDOFF_CLAIMED',          -- collab join：机娘入队
    'LEASE_CLAIMED',            -- collab claim-turn：领租约
    'CONTRIBUTION_SUBMITTED',   -- collab submit：提交成功
    'ATTEMPT_EXPIRED',          -- Worker 宣布超时
    'TICKET_BLOCKED',           -- 前序失败，本棒阻塞
    'SESSION_PAUSED',           -- 中间棒失败，会话暂停
    'SESSION_INVALIDATED',      -- 首棒失败，会话作废
    -- ↓ L18 新增 5 个：全部是【人的决策】留下的痕迹，或人的决策引发的连锁
    'TICKET_RETRIED',           -- ★ 主人 retry 某一棒
    'SESSION_STOPPED',          -- ★ 主人结束协作（交审稿）
    'TICKET_CANCELLED',         -- ★ 收工时取消未完成席位（一条 session 级汇总，不是每票一条）
    'HANDOFF_REISSUED',         -- ★ 重新签发尾令牌（旧的吊销、新的签出）
    'ATTEMPT_FAILED_CLIENT'     -- ★ 机娘自报失败（不用干等 15 分钟超时）
  ));

-- ③ 说明：本课【不动】任何表结构，只动两个 CHECK 约束。
--
--   collaboration_session 的 ck_collab_status 一个字都不改 ——
--   'READY_FOR_OWNER_REVIEW' 与 'TERMINATED' 早在 V012 就写进值集了（那时标注「L18/L20」）。
--   本课兑现的是前者；后者按主人 2026-08-15 的决策【不再使用】：
--
--   ★ 主人的论证（值得记住的一次产品判断）：
--     「完全放弃」与「正常收工」对【草稿】而言结果完全相同 —— 协作不再占着它。
--     至于内容是删是留，那是草稿模块（L19）与删除功能的事，不该由 terminate 回答。
--     ⇒ 别让一个机制回答两个问题。（与 L16 主人否决 handoff TTL 是同一条判据。）
--   ⇒ 协作只有【一个出口】：READY_FOR_OWNER_REVIEW。
--     TERMINATED 留在值集里不删（删它要写迁移，留着不产生它没有成本），
--     'INVALIDATED' 仍由 L17 的 Worker 在首棒失败时自动落 —— 那条路上没有草稿要解锁，
--     主人根本不需要按任何按钮。

-- 8.19审批：加了ticket的cancel状态给终止票链的后续票更新状态。同时补齐了session的各种状态，给审计链