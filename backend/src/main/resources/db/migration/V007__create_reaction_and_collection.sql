-- L09 点赞与收藏。归 forum 模块（社区互动）。
-- 本课两项互动共用同一个【toggle 模式】：点一下加、再点一下取消。
--
-- 点赞复用一张 reaction 表 + 多态 target_type 指向 POST 或 COMMENT（不画外键，应用层保证有效 target_id）。
-- 收藏只针对帖（没有"收藏一条评论"的需求），单列 collection_record。
-- 每张表带 UNIQUE(user+target) 唯一键 —— 这就是【toggle 靠 INSERT IGNORE 判 affectedRows】的物理地基：
--   唯一键保证"同一用户对同一目标只能有一条记录"，并发下来第二条被 IGNORE，DB 替你判断加/删。

CREATE TABLE reaction (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,                      -- 谁点的赞
  target_type VARCHAR(16) NOT NULL,             -- 多态：POST / COMMENT
  target_id BIGINT NOT NULL,                    -- 指向哪篇帖 / 哪条评论
  created_at DATETIME(3) NOT NULL,

  -- 唯一键：同一用户对同一目标只能一条。INSERT IGNORE 的依据；防重复赞的并发兜底。
  UNIQUE KEY uk_reaction_user_target (user_id, target_type, target_id),
  -- 多态查询索引：查某目标的赞（多态，不画外键——POST/COMMENT 异构，FK 画不了）。
  KEY idx_reaction_target (target_type, target_id),

  CONSTRAINT fk_reaction_user FOREIGN KEY (user_id) REFERENCES user_account(id)
);

CREATE TABLE collection_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,                      -- 谁收藏的
  post_id BIGINT NOT NULL,                      -- 收藏哪篇帖（收藏只针对帖）
  created_at DATETIME(3) NOT NULL,

  -- 唯一键：同一用户对同一篇帖只能收藏一次。
  UNIQUE KEY uk_collection_user_post (user_id, post_id),
  KEY idx_collection_post (post_id),

  CONSTRAINT fk_collection_user FOREIGN KEY (user_id) REFERENCES user_account(id),
  CONSTRAINT fk_collection_post FOREIGN KEY (post_id) REFERENCES post(id)
);