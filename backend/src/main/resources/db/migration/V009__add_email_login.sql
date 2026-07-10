-- L11.5 邮箱登录改造（插入课）。把"用户名登录 + 双名字"重构成"邮箱登录 + 唯一 username"。
-- 归 identity 模块。跑在 V008 seed 之后——所以表里已有 alice/bob/carol 存量数据。
--
-- 【面试点：给"运行中的表"加 NOT NULL + UNIQUE 列的标准退避姿势——三步走】
--   直接 ADD COLUMN email VARCHAR NOT NULL UNIQUE 会失败：存量行 email 为空，违反 NOT NULL。
--   正确做法：① 先加可空列 → ② 回填数据 → ③ 再收紧成 NOT NULL + UNIQUE。零停机迁移的雏形。
--
-- 【巧思：净零列数保测试绿】加 email(+1) 又删 display_name(-1)，user_account 列数仍是 13，
--   FlywayMigrationTest 的 columnCount==13 断言自动继续绿。对照 L24 只加 role 一列(13→14)当场打红。

-- ① 先加可空列（存量行先容忍 NULL）
ALTER TABLE user_account ADD COLUMN email VARCHAR(255) NULL AFTER username;

-- ② 回填：给存量 demo 用户造邮箱，并把 username 收敛成 display_name 的"好名"（一个名到处用）。
--    顺序要紧——email 先用 display_name 造好，再把 username 覆盖成 display_name，避免相互踩。
UPDATE user_account
SET email = CONCAT(LOWER(display_name), '@demo.agentlog.local')
WHERE email IS NULL;

UPDATE user_account
SET username = display_name
WHERE display_name IS NOT NULL AND display_name <> '';

-- ③ 回填完，收紧成 NOT NULL + 唯一键（email 从此是登录凭据，必须唯一非空）
ALTER TABLE user_account MODIFY COLUMN email VARCHAR(255) NOT NULL;
ALTER TABLE user_account ADD UNIQUE KEY uk_user_email (email);

-- ④ 砍掉 display_name：展示职责并入 username，一人不再两名
ALTER TABLE user_account DROP COLUMN display_name;
