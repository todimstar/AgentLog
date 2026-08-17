-- L18 施工期修复：去掉两张【流水表】的外键约束 —— 它们是一个真实死锁的根因。
--
-- 迁移编号：V015 是本课的正常改动（状态值集扩展），本文件是【施工期被测试打红后】追加的修复。
--   单独成一条而不是并进 V015，是为了留下"这是踩出来的，不是设计好的"这个痕迹。
--   （铁律仍然是：已经应用过的迁移一个字符都不能改。）
--
-- ═══════════════ 症状 ═══════════════
--   L18 给 claim lease 补上 LEASE_CLAIMED 事件之后，集成测试开始随机报
--     org.springframework.dao.DeadlockLoserDataAccessException
--   报错的 SQL 是一条极简单的 UPDATE collaboration_session SET status='RUNNING' ...
--
-- ═══════════════ 排查 ═══════════════
--   第一层：怀疑是 MyBatis-Plus 的 updateById。它 SET【全部列】，其中三列是外键
--          （owner_user_id / planned_channel_id / tail_handoff_token_id）——
--          即使值没变，InnoDB 也要做外键检查，给三张父表的行各加一把 S 锁。
--          → 改成只 SET status 的精准 UPDATE，死锁从 2 例降到 1 例。【方向对，但没除根】
--
--   第二层：剩下那一例的锁环是这样的 ——
--          T1 (claim lease 业务事务)：持有 contribution_ticket 的 X 锁（闸门那条 UPDATE）
--                                    → 请求 collaboration_session 的 X 锁
--          T2 (AuditListener 异步事务)：INSERT audit_record 时，
--                                    fk_audit_session 要 collaboration_session 的 S 锁（已持有）
--                                    fk_audit_ticket  要 contribution_ticket 的 S 锁（等 T1）
--          ⇒ 成环。
--
-- ═══════════════ 根因 ═══════════════
--   ★ 外键不是"免费的完整性"，它是【隐式的锁】。
--     每插入一行子表记录，InnoDB 都要去父表把被引用的那一行锁住（S 锁），
--     以防它在本事务提交前被删掉。而 audit_record 一次插入要锁【四张】父表的行。
--
--   ★ 审计表是【被业务事务的旁路异步写入】的，它锁的恰恰是业务事务正在改的那些行 ——
--     这是一个天然的死锁温床：两条路径关心的是同一批实体，但顺序完全不同。
--
-- ═══════════════ 为什么解法是"去掉外键"而不是"调整加锁顺序" ═══════════════
--   调整顺序做不到：闸门（改 ticket）必须排在最前面（L15 起的铁律），
--   而审计的插入顺序由 InnoDB 的外键检查决定，我们管不着。
--
--   而外键在这里提供的价值本来就极小：
--     ① audit_record / error_report 的每一列都由【我们自己的代码】写入，
--        不存在"用户传了个不存在的 session_id"这种情形 —— 那才是外键真正防的东西；
--     ② 它们是【只追加的流水】，永不 UPDATE、永不需要级联；
--     ③ 将来这两张表会膨胀到需要归档/分区，外键会让那件事变得很难做。
--
--   ⇒ 判据：★【流水表不建外键】——业界惯例，本项目此前只是没意识到自己也该守它。
--     V014 已经论证了流水表与状态表的三处不同（无 updated_at / 无唯一键 / 大量可空列+JSON），
--     这是【第四处】：不建外键。
--
--   ⚠️ 代价诚实说：从此这两张表可能出现"指向已被删除实体"的行。
--      但本项目【从不物理删除】session/ticket/agent（墓碑删除是 L10 的做法），
--      所以这个代价目前为零。
--
-- ═══════════════ 为什么 L15-L17 一直没炸 ═══════════════
--   并发度不够。审计事件此前只有「超时」「提交」两种，稀疏。
--   L18 补上 LEASE_CLAIMED 之后，每领一次租约就多一次审计写入，撞车概率陡增。
--   ★ 并发缺陷的暴露需要压力，而压力常常由一个看似无关的新功能提供。

-- ① 事故报告：去掉全部 5 个外键。
ALTER TABLE error_report DROP FOREIGN KEY fk_error_owner;
ALTER TABLE error_report DROP FOREIGN KEY fk_error_session;
ALTER TABLE error_report DROP FOREIGN KEY fk_error_ticket;
ALTER TABLE error_report DROP FOREIGN KEY fk_error_attempt;
ALTER TABLE error_report DROP FOREIGN KEY fk_error_agent;

-- ② 审计流水：去掉全部 4 个外键（就是死锁环里的那两把 S 锁的来源）。
ALTER TABLE audit_record DROP FOREIGN KEY fk_audit_owner;
ALTER TABLE audit_record DROP FOREIGN KEY fk_audit_session;
ALTER TABLE audit_record DROP FOREIGN KEY fk_audit_ticket;
ALTER TABLE audit_record DROP FOREIGN KEY fk_audit_agent;

-- ③ 索引【全部保留】。
--   ★ 外键与索引是两件事，很多人会混：
--     外键 = 写入时的完整性检查（带锁）；索引 = 查询时的加速结构（不带锁）。
--     MySQL 建外键时会自动建索引，所以去掉外键容易连带把索引也想删掉 —— 千万别。
--   时间线页正是靠 idx_audit_session_created 按 session 拉全量流水的。
--
-- ④ contribution_attempt.error_report_id 的外键 fk_attempt_error 【保留】。
--   它的方向相反（状态表引用流水表），且写入它的 linkAttemptToErrorReport 与
--   插入 error_report 在【同一个事务】里，不与业务事务争锁，不在死锁环上。
