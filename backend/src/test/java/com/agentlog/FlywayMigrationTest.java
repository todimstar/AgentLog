package com.agentlog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * L03 验收：在【全新的空 MySQL】上跑 Flyway 迁移，验证 user_account 表被正确建出来。
 *
 * 为什么用 Testcontainers 而不是连本地 3307 那个开发库？
 *   - 开发库里可能已经有表（你手动启动 app 建过）→ 测不出"从空库迁移"是否成立
 *   - CI 机器上没有你的 3307 容器 → 测试必须自带数据库
 *   Testcontainers 每次跑都拉一个全新临时容器，测完销毁，保证可重复、不依赖环境、不污染开发库。
 *
 * 为什么用 mysql:8.0 而不是 H2？
 *   H2 是内存玩具库，SQL 方言和 MySQL 有差异，会"测试绿、生产挂"。
 *   这里用和生产/本地同款的 mysql:8.0，迁移在真 MySQL 上验证，测过就是真能用。
 */
@JdbcTest // 只装 JDBC 相关（DataSource + JdbcTemplate + Flyway），不启动 Web/完整应用，比 @SpringBootTest 轻
// @JdbcTest 默认会用内嵌 H2 替换数据源；replace=NONE 阻止替换，强制用下面 Testcontainers 的真 MySQL。
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Testcontainers // 启用 Testcontainers 生命周期管理：自动 start/stop 下面带 @Container 的容器
class FlywayMigrationTest {

    // @Container：声明一个受 Testcontainers 管理的容器。static = 整个测试类共享一个容器（省启动开销）。
    // mysql:8.0 与 docker-compose.local.yml、生产目标版本保持一致——避免"测试和生产数据库不是一个东西"。
    @Container
    @ServiceConnection // Spring Boot 3 的魔法：自动把这个容器的 url/user/password 注入数据源，
                       // 无需手写 spring.datasource.url。底层等价于老写法 @DynamicPropertySource 手动塞属性。
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    // @JdbcTest 默认会执行 Flyway 迁移（因为 classpath 上有 flyway 且 enabled）。
    // 容器是空库 → Flyway 会从 V001 开始跑，跑完 user_account 就该存在。
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesUserAccountTableOnEmptyDatabase() {
        // 用 information_schema 查"当前库里叫 user_account 的表有几张"。
        // 迁移成功 → 应为 1；迁移没跑或表名错 → 0。
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'user_account'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);

        // 进一步验证"表结构对"：查 user_account 的列数，应等于 V001 定义的 13 个字段。
        // 查 information_schema.columns（MySQL 存"每张表有哪些列"的系统库）验证迁移结果，无需插数据。
        Integer columnCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns "
                    + "WHERE table_schema = DATABASE() AND table_name = 'user_account'",
            Integer.class);
        assertThat(columnCount).isEqualTo(13);
    }
    /**
     * L06 验收：V002-V005 在空库上能成功迁移。
     * 这个测试是 V005 外键依赖顺序的"探针"——若 V002/V004 没先建好，
     * post 的外键根本建不出来，下面的外键数断言会直接红。
     */
    @Test
    void migratesContentCoreTablesOnEmptyDatabase() {
        // 一次性验证 L06 新增的 6 张核心内容表都建出来了
        Integer contentTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('post','contribution','draft','draft_block','post_version','post_version_block')",
                Integer.class);
        assertThat(contentTableCount).isEqualTo(6);

        // 验证 post 的 3 个外键真的建上了（owner / channel / cover）。
        // 外键能建成 = 它指向的 user_account/forum_channel/media_object 都已存在 = 依赖顺序正确。
        Integer postForeignKeyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_type = 'FOREIGN KEY' AND table_name = 'post'",
                Integer.class);
        assertThat(postForeignKeyCount).isEqualTo(3);
    }

    /**
     * L08 验收：V006 comment 表建出来 + 5 个外键（post/author/root/parent/reply_to）+ depth CHECK 约束。
     * root/parent/reply_to 三个自引用外键能建成 = comment 表先于外键存在 = 自引用顺序正确。
     */
    @Test
    void migratesCommentTableOnEmptyDatabase() {
        Integer commentTableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'comment'",
                Integer.class);
        assertThat(commentTableCount).isEqualTo(1);

        Integer commentForeignKeyCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_type = 'FOREIGN KEY' AND table_name = 'comment'",
                Integer.class);
        assertThat(commentForeignKeyCount).isEqualTo(5);

        // depth CHECK 不变量存在（MySQL 8 把 CHECK 记在 table_constraints，type=CHECK）。
        Integer depthCheckCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_type = 'CHECK' AND table_name = 'comment' "
                        + "AND constraint_name = 'ck_comment_depth'",
                Integer.class);
        assertThat(depthCheckCount).isEqualTo(1);
    }

    /**
     * L09 验收：V007 reaction + collection_record 建出来 + 各自唯一键（toggle 防重复赞/收藏的物理地基）。
     * reaction 单外键（user），collection 双外键（user+post）——多态 reaction 不画到 post/comment 的外键。
     */
    @Test
    void migratesReactionAndCollectionTablesOnEmptyDatabase() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN ('reaction','collection_record')",
                Integer.class);
        assertThat(tableCount).isEqualTo(2);

        // reaction 唯一键（user+target_type+target_id）= toggle INSERT IGNORE 的依据
        Integer reactionUniqueKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'reaction' AND non_unique = 0",
                Integer.class);
        assertThat(reactionUniqueKeys).isGreaterThan(0);

        // collection 唯一键（user+post）
        Integer collectionUniqueKeys = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 'collection_record' AND non_unique = 0",
                Integer.class);
        assertThat(collectionUniqueKeys).isGreaterThan(0);
    }

    /**
     * L15 验收：V012 ACPP 三表建出来，且三个"设计不变量"真的落到了物理约束上。
     *
     * 这个测试是 V012 里【循环外键】的探针——collaboration_session.tail_handoff_token_id 指向
     * handoff_token.id，而 handoff_token.session_id 又指回 collaboration_session.id。
     * 两张表互指成环，谁都不能先于对方建完外键，必须"先建表、后 ALTER 补"。
     * 若写成 CREATE TABLE 内联外键，Flyway 会在这里直接炸（errno 150）。
     */
    @Test
    void migratesCollaborationTablesOnEmptyDatabase() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name IN "
                        + "('collaboration_session','contribution_ticket','handoff_token')",
                Integer.class);
        assertThat(tableCount).isEqualTo(3);

        // ① D-15 补列真的建上了（蓝图 V009 缺这三列，契约却要 title+channelId）。
        Integer plannedColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'collaboration_session' "
                        + "AND column_name IN ('planned_title','planned_channel_id','planned_summary')",
                Integer.class);
        assertThat(plannedColumns).isEqualTo(3);

        // ② 成环的那条外键补上了（本测试的核心：证明"先建表后 ALTER"这一步没被省掉）。
        Integer tailFk = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY' "
                        + "AND table_name = 'collaboration_session' "
                        + "AND constraint_name = 'fk_collab_tail_handoff'",
                Integer.class);
        assertThat(tailFk).isEqualTo(1);

        // ③ V005 留的坑填了：contribution 的 session_id / ticket_id 两列终于有外键指向。
        Integer contributionAcppFks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY' "
                        + "AND table_name = 'contribution' "
                        + "AND constraint_name IN ('fk_contribution_session','fk_contribution_ticket')",
                Integer.class);
        assertThat(contributionAcppFks).isEqualTo(2);

        // ④ 三个状态机的合法值集锁在 CHECK 约束里（写错字面量插入即被拒，不必等运行时发现）。
        Integer statusChecks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND constraint_type = 'CHECK' "
                        + "AND constraint_name IN ('ck_collab_status','ck_ticket_status','ck_handoff_status')",
                Integer.class);
        assertThat(statusChecks).isEqualTo(3);

        // ⑤ 因果链的自引用外键（predecessor）——顺序不靠时间戳，靠这条链。
        Integer predecessorFk = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints "
                        + "WHERE table_schema = DATABASE() AND constraint_type = 'FOREIGN KEY' "
                        + "AND table_name = 'contribution_ticket' "
                        + "AND constraint_name = 'fk_ticket_predecessor'",
                Integer.class);
        assertThat(predecessorFk).isEqualTo(1);

        // ⑥ 宽度审计：source_tool 必须 >= 来源 agent_acting_session.source_tool 的 64，否则严格模式下超长报错。
        //    蓝图 V009 此处写的是 32（比来源窄），我们取 64 与来源对齐。
        Integer sourceToolWidth = jdbcTemplate.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'contribution_ticket' "
                        + "AND column_name = 'source_tool'",
                Integer.class);
        assertThat(sourceToolWidth).isGreaterThanOrEqualTo(64);
    }
}
