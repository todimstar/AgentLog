-- L09 验收后补一批稳定演示数据，方便从 L05-L09 做浏览器回归。
-- 这不是业务表结构课，而是开发库/空测试库的 seed：新库迁完就有频道、用户、帖子、评论、点赞、收藏可测。
-- 写成幂等风格，是为了本机库如果先手工执行一次，后续 Flyway 再跑也不会重复造数据。

SET @now = CURRENT_TIMESTAMP(3);
SET @pwd = '$2a$10$qsRbCpnji/k5ie1k6vSfk.nLtBmLGugqCRPeVektTNPes46Bixwu.';

-- 1) 用户：演示账号使用 demo_ 前缀，避免占用 L05 WebAuthIntegrationTest 自己要注册的 alice/bob。
INSERT INTO user_account (username, password_hash, display_name, short_bio, status, created_at, updated_at)
VALUES
  ('demo_alice', @pwd, 'Alice', 'AgentLog 的主人账号，负责发布开发日志。', 'ACTIVE', @now, @now),
  ('demo_bob', @pwd, 'Bob', '喜欢挑边界条件的后端同学。', 'ACTIVE', @now, @now),
  ('demo_carol', @pwd, 'Carol', '关注前端体验和验收路径。', 'ACTIVE', @now, @now)
ON DUPLICATE KEY UPDATE updated_at = updated_at;

UPDATE user_account
SET password_hash = @pwd, display_name = 'Alice', short_bio = 'AgentLog 的主人账号，负责发布开发日志。',
    status = 'ACTIVE', updated_at = @now
WHERE username = 'demo_alice';
UPDATE user_account
SET password_hash = @pwd, display_name = 'Bob', short_bio = '喜欢挑边界条件的后端同学。',
    status = 'ACTIVE', updated_at = @now
WHERE username = 'demo_bob';
UPDATE user_account
SET password_hash = @pwd, display_name = 'Carol', short_bio = '关注前端体验和验收路径。',
    status = 'ACTIVE', updated_at = @now
WHERE username = 'demo_carol';

SELECT id INTO @alice_id FROM user_account WHERE username = 'demo_alice';
SELECT id INTO @bob_id FROM user_account WHERE username = 'demo_bob';
SELECT id INTO @carol_id FROM user_account WHERE username = 'demo_carol';

-- 2) 频道：修复早期手工 SQL 造成的 UTF-8 乱码，并补多个频道用于 Feed 筛选。
INSERT INTO forum_channel (slug, name, description, icon, sort_order, enabled, post_count, created_at, updated_at)
VALUES
  ('dev', '开发日志', '功能开发、踩坑复盘、课程推进记录。', 'code-log', 0, TRUE, 0, @now, @now),
  ('ai-collab', 'AI 协作', '人与 AI 结对开发、提示词、Agent 流水线。', 'spark', 10, TRUE, 0, @now, @now),
  ('ops-review', '验收复盘', '测试、部署、数据修复和回归清单。', 'checklist', 20, TRUE, 0, @now, @now)
ON DUPLICATE KEY UPDATE updated_at = updated_at;

UPDATE forum_channel
SET name = '开发日志',
    description = '功能开发、踩坑复盘、课程推进记录。',
    icon = 'code-log',
    updated_at = @now
WHERE slug = 'dev';
UPDATE forum_channel
SET name = 'AI 协作',
    description = '人与 AI 结对开发、提示词、Agent 流水线。',
    icon = 'spark',
    sort_order = 10,
    enabled = TRUE,
    updated_at = @now
WHERE slug = 'ai-collab';
UPDATE forum_channel
SET name = '验收复盘',
    description = '测试、部署、数据修复和回归清单。',
    icon = 'checklist',
    sort_order = 20,
    enabled = TRUE,
    updated_at = @now
WHERE slug = 'ops-review';

SELECT id INTO @dev_channel_id FROM forum_channel WHERE slug = 'dev';
SELECT id INTO @ai_channel_id FROM forum_channel WHERE slug = 'ai-collab';
SELECT id INTO @ops_channel_id FROM forum_channel WHERE slug = 'ops-review';

-- 3) 修复已发布版本里的分区名快照。详情页读的是 post_version.channel_name_snapshot，不修这里还会继续乱码。
UPDATE post_version pv
JOIN forum_channel c ON c.id = pv.channel_id_snapshot
SET pv.channel_name_snapshot = c.name
WHERE pv.channel_name_snapshot <> c.name;

-- 4) 帖子：每篇都走 post + post_version + post_version_block 的正式发布结构，方便测 Feed、详情、评论、点赞、收藏。
INSERT INTO post (
  owner_user_id, channel_id, visibility_status, title_cache, summary_cache, content_origin_cache,
  iteration_count, view_count, like_count, comment_count, collection_count, hot_score,
  is_pinned, is_essence, published_at, version, created_at, updated_at
)
SELECT @alice_id, @dev_channel_id, 'PUBLISHED',
       'L06 发帖闭环复盘：从草稿到发布快照',
       '用一篇真实日志串起草稿、贡献、版本快照和公开详情。',
       'HUMAN_ONLY', 1, 32, 0, 0, 0, 18.5, TRUE, FALSE,
       TIMESTAMPADD(HOUR, -6, @now), 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM post WHERE title_cache = 'L06 发帖闭环复盘：从草稿到发布快照');

SELECT id INTO @post_l06 FROM post WHERE title_cache = 'L06 发帖闭环复盘：从草稿到发布快照' LIMIT 1;

INSERT INTO post_version (
  post_id, version_no, title_snapshot, summary_snapshot, channel_id_snapshot, channel_name_snapshot,
  content_origin, moderation_status, owner_approved_by_user_id, owner_approved_at, published_at, created_at
)
SELECT @post_l06, 1,
       'L06 发帖闭环复盘：从草稿到发布快照',
       '用一篇真实日志串起草稿、贡献、版本快照和公开详情。',
       @dev_channel_id, '开发日志', 'HUMAN_ONLY', 'NOT_REQUIRED',
       @alice_id, TIMESTAMPADD(HOUR, -6, @now), TIMESTAMPADD(HOUR, -6, @now), @now
WHERE NOT EXISTS (SELECT 1 FROM post_version WHERE post_id = @post_l06 AND version_no = 1);

SELECT id INTO @version_l06 FROM post_version WHERE post_id = @post_l06 AND version_no = 1;
UPDATE post SET current_published_version_id = @version_l06 WHERE id = @post_l06;

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, display_order, content_snapshot, created_at)
SELECT @version_l06, 'OWNER', @alice_id, 0,
       '这篇用来回测 L06：创建草稿时会写 post/draft/contribution/draft_block，发布时复制成 post_version/post_version_block，再回填 post 的缓存字段。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l06 AND display_order = 0);

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, display_order, content_snapshot, created_at)
SELECT @version_l06, 'OWNER', @alice_id, 1,
       '验收点：Feed 只读 post 缓存字段，详情页沿 current_published_version_id 读快照，不去扫草稿表。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l06 AND display_order = 1);

INSERT INTO post (
  owner_user_id, channel_id, visibility_status, title_cache, summary_cache, content_origin_cache,
  iteration_count, view_count, like_count, comment_count, collection_count, hot_score,
  is_pinned, is_essence, published_at, version, created_at, updated_at
)
SELECT @bob_id, @ai_channel_id, 'PUBLISHED',
       'L08 评论模型：逻辑无限回复，物理两层存储',
       'B站式回复不拒绝二级回复，而是扁平挂回同一楼。',
       'AI_ASSISTED', 2, 58, 0, 0, 0, 26.2, FALSE, TRUE,
       TIMESTAMPADD(HOUR, -3, @now), 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM post WHERE title_cache = 'L08 评论模型：逻辑无限回复，物理两层存储');

SELECT id INTO @post_l08 FROM post WHERE title_cache = 'L08 评论模型：逻辑无限回复，物理两层存储' LIMIT 1;

INSERT INTO post_version (
  post_id, version_no, title_snapshot, summary_snapshot, channel_id_snapshot, channel_name_snapshot,
  content_origin, moderation_status, owner_approved_by_user_id, owner_approved_at, published_at, created_at
)
SELECT @post_l08, 1,
       'L08 评论模型：逻辑无限回复，物理两层存储',
       'B站式回复不拒绝二级回复，而是扁平挂回同一楼。',
       @ai_channel_id, 'AI 协作', 'AI_ASSISTED', 'NOT_REQUIRED',
       @bob_id, TIMESTAMPADD(HOUR, -3, @now), TIMESTAMPADD(HOUR, -3, @now), @now
WHERE NOT EXISTS (SELECT 1 FROM post_version WHERE post_id = @post_l08 AND version_no = 1);

SELECT id INTO @version_l08 FROM post_version WHERE post_id = @post_l08 AND version_no = 1;
UPDATE post SET current_published_version_id = @version_l08 WHERE id = @post_l08;

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, source_tool, display_order, content_snapshot, created_at)
SELECT @version_l08, 'OWNER', @bob_id, 'Claude Code', 0,
       'root_comment_id 表示楼，parent_comment_id 表示直接回复谁，reply_to_comment_id 表示展示时 @ 谁。回复二级评论时 depth 仍然是 2。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l08 AND display_order = 0);

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, source_tool, display_order, content_snapshot, created_at)
SELECT @version_l08, 'OWNER', @bob_id, 'Claude Code', 1,
       '这篇用来测评论树：楼主、回复楼主、回复二级评论都能在同一楼下平铺显示。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l08 AND display_order = 1);

INSERT INTO post (
  owner_user_id, channel_id, visibility_status, title_cache, summary_cache, content_origin_cache,
  iteration_count, view_count, like_count, comment_count, collection_count, hot_score,
  is_pinned, is_essence, published_at, version, created_at, updated_at
)
SELECT @carol_id, @ops_channel_id, 'PUBLISHED',
       'L09 toggle 验收清单：affectedRows 与 GREATEST',
       '点赞和收藏都用同一套 toggle 心智模型，适合反复点按钮测试。',
       'HUMAN_ONLY', 1, 21, 0, 0, 0, 12.8, FALSE, FALSE,
       TIMESTAMPADD(HOUR, -1, @now), 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM post WHERE title_cache = 'L09 toggle 验收清单：affectedRows 与 GREATEST');

SELECT id INTO @post_l09 FROM post WHERE title_cache = 'L09 toggle 验收清单：affectedRows 与 GREATEST' LIMIT 1;

INSERT INTO post_version (
  post_id, version_no, title_snapshot, summary_snapshot, channel_id_snapshot, channel_name_snapshot,
  content_origin, moderation_status, owner_approved_by_user_id, owner_approved_at, published_at, created_at
)
SELECT @post_l09, 1,
       'L09 toggle 验收清单：affectedRows 与 GREATEST',
       '点赞和收藏都用同一套 toggle 心智模型，适合反复点按钮测试。',
       @ops_channel_id, '验收复盘', 'HUMAN_ONLY', 'NOT_REQUIRED',
       @carol_id, TIMESTAMPADD(HOUR, -1, @now), TIMESTAMPADD(HOUR, -1, @now), @now
WHERE NOT EXISTS (SELECT 1 FROM post_version WHERE post_id = @post_l09 AND version_no = 1);

SELECT id INTO @version_l09 FROM post_version WHERE post_id = @post_l09 AND version_no = 1;
UPDATE post SET current_published_version_id = @version_l09 WHERE id = @post_l09;

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, display_order, content_snapshot, created_at)
SELECT @version_l09, 'OWNER', @carol_id, 0,
       'INSERT IGNORE 先尝试插入 reaction/collection_record，affectedRows=1 表示刚加上，affectedRows=0 表示唯一键已存在，于是删除变取消。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l09 AND display_order = 0);

INSERT INTO post_version_block (post_version_id, author_type, author_user_id, display_order, content_snapshot, created_at)
SELECT @version_l09, 'OWNER', @carol_id, 1,
       '计数更新使用 GREATEST(0, count + delta)，保证异常情况下也不会出现 -1 赞或 -1 收藏。',
       @now
WHERE NOT EXISTS (SELECT 1 FROM post_version_block WHERE post_version_id = @version_l09 AND display_order = 1);

-- 5) 评论：补足顶层、二级、回复二级、软删占位的可视化样本。
INSERT INTO comment (post_id, author_user_id, root_comment_id, parent_comment_id, reply_to_comment_id, depth, content, status, like_count, version, created_at, updated_at)
SELECT @post_l08, @alice_id, NULL, NULL, NULL, 1, '这个模型我一开始以为要禁三级，后来发现应该扁平到同一楼。', 'VISIBLE', 0, 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM comment WHERE post_id = @post_l08 AND content = '这个模型我一开始以为要禁三级，后来发现应该扁平到同一楼。');

SELECT id INTO @l08_root FROM comment WHERE post_id = @post_l08 AND content = '这个模型我一开始以为要禁三级，后来发现应该扁平到同一楼。' LIMIT 1;
UPDATE comment SET root_comment_id = @l08_root WHERE id = @l08_root AND root_comment_id IS NULL;

INSERT INTO comment (post_id, author_user_id, root_comment_id, parent_comment_id, reply_to_comment_id, depth, content, status, like_count, version, created_at, updated_at)
SELECT @post_l08, @bob_id, @l08_root, @l08_root, @l08_root, 2, '对，物理两层不等于逻辑不能继续回复。', 'VISIBLE', 0, 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM comment WHERE post_id = @post_l08 AND content = '对，物理两层不等于逻辑不能继续回复。');

SELECT id INTO @l08_reply FROM comment WHERE post_id = @post_l08 AND content = '对，物理两层不等于逻辑不能继续回复。' LIMIT 1;

INSERT INTO comment (post_id, author_user_id, root_comment_id, parent_comment_id, reply_to_comment_id, depth, content, status, like_count, version, created_at, updated_at)
SELECT @post_l08, @carol_id, @l08_root, @l08_reply, @l08_reply, 2, '我回复的是二级评论，但展示时仍在这栋楼下面。', 'VISIBLE', 0, 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM comment WHERE post_id = @post_l08 AND content = '我回复的是二级评论，但展示时仍在这栋楼下面。');

INSERT INTO comment (post_id, author_user_id, root_comment_id, parent_comment_id, reply_to_comment_id, depth, content, status, like_count, version, created_at, updated_at)
SELECT @post_l09, @bob_id, NULL, NULL, NULL, 1, '这篇适合专门测点赞、收藏、未登录跳转和 CSRF。', 'VISIBLE', 0, 0, @now, @now
WHERE NOT EXISTS (SELECT 1 FROM comment WHERE post_id = @post_l09 AND content = '这篇适合专门测点赞、收藏、未登录跳转和 CSRF。');

SELECT id INTO @l09_root FROM comment WHERE post_id = @post_l09 AND content = '这篇适合专门测点赞、收藏、未登录跳转和 CSRF。' LIMIT 1;
UPDATE comment SET root_comment_id = @l09_root WHERE id = @l09_root AND root_comment_id IS NULL;

-- 6) 点赞/收藏：补真实记录，再用真实记录反算计数，避免缓存列和明细表不一致。
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
SELECT @bob_id, 'POST', @post_l06, @now;
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
SELECT @carol_id, 'POST', @post_l06, @now;
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
SELECT @bob_id, 'POST', @post_l08, @now;
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
SELECT @carol_id, 'POST', @post_l08, @now;
INSERT IGNORE INTO reaction (user_id, target_type, target_id, created_at)
SELECT @carol_id, 'COMMENT', @l08_reply, @now;

INSERT IGNORE INTO collection_record (user_id, post_id, created_at)
SELECT @carol_id, @post_l06, @now;
INSERT IGNORE INTO collection_record (user_id, post_id, created_at)
SELECT @bob_id, @post_l09, @now;

UPDATE post p
SET like_count = (SELECT COUNT(*) FROM reaction r WHERE r.target_type = 'POST' AND r.target_id = p.id),
    collection_count = (SELECT COUNT(*) FROM collection_record cr WHERE cr.post_id = p.id),
    comment_count = (SELECT COUNT(*) FROM comment c WHERE c.post_id = p.id AND c.status = 'VISIBLE'),
    updated_at = @now
WHERE p.visibility_status = 'PUBLISHED';

UPDATE comment c
SET like_count = (SELECT COUNT(*) FROM reaction r WHERE r.target_type = 'COMMENT' AND r.target_id = c.id),
    updated_at = @now;

UPDATE forum_channel c
SET post_count = (
      SELECT COUNT(*) FROM post p
      WHERE p.channel_id = c.id AND p.visibility_status = 'PUBLISHED'
    ),
    updated_at = @now;
