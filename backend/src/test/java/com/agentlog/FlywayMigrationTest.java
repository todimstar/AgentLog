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
}
