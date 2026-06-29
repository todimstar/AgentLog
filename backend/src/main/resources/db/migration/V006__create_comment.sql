-- L08 评论：B站式"逻辑无限回复 + 物理永远两层"。归 forum 模块（社区互动）。
--
-- 模型灵魂是 root_comment_id（楼）：无论回复谁，新评论永远挂到"目标所属那层楼"的楼主下，
-- depth 永远=2。三级状态【根本无法被表达】，不是产生后被拦——这才是真正的禁三级。
CREATE TABLE comment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id BIGINT NOT NULL,                        -- 评论挂在哪篇帖子下
  author_user_id BIGINT NOT NULL,                 -- 评论人（L08 只有人评论，机娘评论后续课）

  -- —— B站两层模型的三根支柱 ——
  root_comment_id BIGINT NULL,                    -- 楼：一级指向自己；二级指向其所属一级。同楼所有评论 root 相同
  parent_comment_id BIGINT NULL,                  -- 直接父：一级为 NULL；二级指向被回复的那条（可以是另一条二级）
  reply_to_comment_id BIGINT NULL,               -- @谁：展示"回复 @某人"，纯展示，不加深层级
  depth TINYINT NOT NULL DEFAULT 1,               -- 楼层深度：1=一级 / 2=二级。永远只有这两个值

  content VARCHAR(4000) NOT NULL,                -- 评论正文（对齐契约 maxLength 4000）
  status VARCHAR(16) NOT NULL DEFAULT 'VISIBLE', -- VISIBLE / DELETED（软删占位，保楼层，不物理删）
  like_count BIGINT NOT NULL DEFAULT 0,          -- 评论可被点赞（L09 接 reaction）

  version BIGINT NOT NULL DEFAULT 0,             -- 乐观锁（软删/未来编辑防并发覆盖）
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,

  -- 把"最多两级"下沉为 DB 不变量：插入 depth=3 直接报错。
  -- 救的不是当前正确代码，而是未来的 bug（批量导入/重构/新入口忘判断）——给还没写出的 bug 兜底。
  CONSTRAINT ck_comment_depth CHECK (depth IN (1, 2)),

  -- 按楼查询主索引：定位某帖某楼全部评论按时间排，正是"评论树"的读取访问路径。
  -- root_comment_id 第二列：一条 SQL 平铺拉全帖，同楼评论天然聚在一起（前端顺序分组成两层）。
  KEY idx_comment_post_root (post_id, root_comment_id, created_at, id),

  CONSTRAINT fk_comment_post FOREIGN KEY (post_id) REFERENCES post(id),
  CONSTRAINT fk_comment_author FOREIGN KEY (author_user_id) REFERENCES user_account(id),
  CONSTRAINT fk_comment_root FOREIGN KEY (root_comment_id) REFERENCES comment(id),
  CONSTRAINT fk_comment_parent FOREIGN KEY (parent_comment_id) REFERENCES comment(id),
  CONSTRAINT fk_comment_reply_to FOREIGN KEY (reply_to_comment_id) REFERENCES comment(id)
);
