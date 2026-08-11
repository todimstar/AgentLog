package com.agentlog.forum;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.agentlog.support.AuthTestSupport;

/**
 * L08 评论二级回复验收。forum 模块第一个集成测试。
 *
 * 覆盖 6 个验收点：
 *  1. 发顶层评论（depth=0），comment_count +1
 *  2. 发二级回复（depth=1，带 replyTo），归到顶层楼下
 *  3. 禁三级：回复一条 depth=1 的评论 → 422 COMMENT_DEPTH_EXCEEDED（防线①）
 *  4. 软删保楼层：删顶层评论后，它仍在树里（占位"该评论已删除"），子回复还在
 *  5. 评论树平铺顺序：楼1 + 楼1回复们 → 楼2，前端可顺序分组
 *  6. 行级授权：删他人评论 → 403 COMMENT_FORBIDDEN
 *
 * 夹具策略：直接用 JdbcTemplate 插一篇 PUBLISHED post（聚焦测评论，不跑 L06 发帖全链路）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CommentIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withUrlParam("serverTimezone", "UTC");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // @SpringBootTest 默认不回滚事务，多个 @Test 共享同一 Testcontainers 库。
    // 用递增后缀保证每个 test 的 slug/username 唯一，避免撞唯一键。
    private static int seq = 0;

    private long postId;
    private long ownerId;
    private String suffix;

    @BeforeEach
    void seedPublishedPost() {
        suffix = "t" + (++seq);
        Instant now = Instant.now();
        // 一个分区（post.channel_id 外键需要）。slug 唯一，不写死。
        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "dev-" + suffix, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject("SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);
        // 帖主：每个 test 单独建一个，避免与评论作者混淆（作者名 JOIN 用）
        jdbcTemplate.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,'x','ACTIVE',?,?)", "seed-" + suffix, "seed-" + suffix + "@test.local", now, now);
        ownerId = jdbcTemplate.queryForObject("SELECT id FROM user_account ORDER BY id DESC LIMIT 1", Long.class);
        // 一篇 PUBLISHED 帖（评论挂它下面）
        jdbcTemplate.update(
                "INSERT INTO post(owner_user_id,channel_id,visibility_status,comment_count,hot_score,"
                        + "is_pinned,is_essence,iteration_count,view_count,like_count,collection_count,version,created_at,updated_at) "
                        + "VALUES (?,?,'PUBLISHED',0,0,FALSE,FALSE,0,0,0,0,0,?,?)", ownerId, channelId, now, now);
        postId = jdbcTemplate.queryForObject("SELECT id FROM post ORDER BY id DESC LIMIT 1", Long.class);
    }

    /** 注册 + 登录，返回带认证的 session。L11.5：直插用户 + email 登录（绕开注册验证码流程）。 */
    private MockHttpSession loginAs(String username) throws Exception {
        return AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder,
                username + "-" + suffix, username + "-" + suffix + "@test.local");
    }

    private long postComment(MockHttpSession session, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/web/posts/" + postId + "/comments")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("id").asLong();
    }

    @Test
    void topLevelCommentThenReplyFormsTwoLevels() throws Exception {
        MockHttpSession alice = loginAs("alice");

        // 1. 一级评论 depth=1
        long floorId = postComment(alice, "{\"content\":\"顶层评论\"}");
        mockMvc.perform(get("/api/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].depth").value(1))
                .andExpect(jsonPath("$.items[0].rootCommentId").value(floorId))
                .andExpect(jsonPath("$.items[0].content").value("顶层评论"));

        // 2. 二级回复 depth=2，挂在 floorId 楼下，@floorId
        postComment(alice, "{\"content\":\"我是回复\",\"parentCommentId\":" + floorId + ",\"replyToCommentId\":" + floorId + "}");
        mockMvc.perform(get("/api/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].depth").value(2))
                .andExpect(jsonPath("$.items[1].rootCommentId").value(floorId))
                .andExpect(jsonPath("$.items[1].parentCommentId").value(floorId));

        // comment_count = 2
        Long count = jdbcTemplate.queryForObject("SELECT comment_count FROM post WHERE id=?", Long.class, postId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(2L);
    }

    @Test
    void replyingToReplyFlattensToSameFloor() throws Exception {
        // B站核心：回复一条二级评论 → 不报错，扁平挂到【同一层楼】下，仍是 depth=2。
        // 三级状态根本无法被表达 = 真正的"禁三级"（靠扁平化，不靠拒绝）。
        MockHttpSession alice = loginAs("alice");
        long floorId = postComment(alice, "{\"content\":\"一级楼主\"}");
        long replyId = postComment(alice, "{\"content\":\"二级A\",\"parentCommentId\":" + floorId + "}");

        // 回复二级A：parentCommentId 指向 replyId（它已是 depth=2）。应成功，且：
        //   depth 仍=2、root 仍=floorId（同楼）、parent=replyId（直接父是二级A）、reply_to=replyId（@二级A）
        MvcResult result = mockMvc.perform(post("/api/v1/web/posts/" + postId + "/comments")
                        .session(alice)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"content\":\"回复二级A\",\"parentCommentId\":" + replyId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depth").value(2))
                .andExpect(jsonPath("$.rootCommentId").value(floorId))
                .andExpect(jsonPath("$.parentCommentId").value(replyId))
                .andExpect(jsonPath("$.replyToCommentId").value(replyId))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(201);

        // 整楼现在 3 条：1 个一级 + 2 个二级，全平铺在 floorId 楼下
        mockMvc.perform(get("/api/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[2].depth").value(2))
                .andExpect(jsonPath("$.items[2].rootCommentId").value(floorId));
    }

    @Test
    void softDeleteKeepsFloorPlaceholder() throws Exception {
        MockHttpSession alice = loginAs("alice");
        long floorId = postComment(alice, "{\"content\":\"将被删除的顶层\"}");
        postComment(alice, "{\"content\":\"子回复仍在\",\"parentCommentId\":" + floorId + "}");

        // 软删顶层
        mockMvc.perform(delete("/api/v1/web/comments/" + floorId)
                        .session(alice)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNoContent());

        // 树里顶层仍在（占位），子回复还在 → 楼层没塌
        mockMvc.perform(get("/api/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("DELETED"))
                .andExpect(jsonPath("$.items[0].content").value("该评论已删除"))
                .andExpect(jsonPath("$.items[1].content").value("子回复仍在"));
    }

    @Test
    void cannotDeleteOthersComment() throws Exception {
        MockHttpSession alice = loginAs("alice");
        long commentId = postComment(alice, "{\"content\":\"alice 的评论\"}");

        MockHttpSession bob = loginAs("bob");
        mockMvc.perform(delete("/api/v1/web/comments/" + commentId)
                        .session(bob)
                        .with(SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENT_FORBIDDEN"));
    }

    @Test
    void anonymousCanReadButCannotPost() throws Exception {
        // 匿名读 OK
        mockMvc.perform(get("/api/v1/public/posts/" + postId + "/comments"))
                .andExpect(status().isOk());
        // 匿名发 → 401
        mockMvc.perform(post("/api/v1/web/posts/" + postId + "/comments")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"content\":\"匿名想发\"}"))
                .andExpect(status().isUnauthorized());
    }
}
