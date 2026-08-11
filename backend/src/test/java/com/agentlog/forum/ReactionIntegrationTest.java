package com.agentlog.forum;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * L09 点赞/收藏验收。forum 模块第二个集成测试。
 *
 * 覆盖 8 个验收点：
 *  1. 点赞 toggle：第一次 active=true count+1；再点 active=false count-1
 *  2. 计数防变负：连续取消不会让 like_count 变 -1（GREATEST 兜底，间接验证：取消后再取消 count 保持 0）
 *  3. 收藏 toggle：同构验证
 *  4. 评论点赞（多态）：targetType=COMMENT + commentId，给 comment.like_count 增减
 *  5. 多态隔离：赞帖和赞评论互不影响（同 targetId 不同 targetType 是两条记录）
 *  6. 未登录 401：匿名点不了赞
 *  7. 登录态 state 查询：刷新详情页后能恢复当前用户是否已赞/收藏
 *  8. state 查询仍是登录态接口：匿名只能看公开详情，不能读"我是否已赞/收藏"
 *
 * 夹具：JdbcTemplate 直插 PUBLISHED 帖（聚焦测 toggle，不跑发帖全链路）+ 唯一后缀防 @BeforeEach 间唯一键残留。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ReactionIntegrationTest {

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

    private static int seq = 0;
    private String suffix;
    private long postId;
    private long commentId;

    @BeforeEach
    void seed() {
        suffix = "r" + (++seq);
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "ch-" + suffix, "测试分区", now, now);
        Long channelId = jdbcTemplate.queryForObject("SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,'x','ACTIVE',?,?)", "owner-" + suffix, "owner-" + suffix + "@test.local", now, now);
        Long ownerId = jdbcTemplate.queryForObject("SELECT id FROM user_account ORDER BY id DESC LIMIT 1", Long.class);
        jdbcTemplate.update(
                "INSERT INTO post(owner_user_id,channel_id,visibility_status,comment_count,hot_score,"
                        + "is_pinned,is_essence,iteration_count,view_count,like_count,collection_count,version,created_at,updated_at) "
                        + "VALUES (?,?,'PUBLISHED',0,0,FALSE,FALSE,0,0,0,0,0,?,?)", ownerId, channelId, now, now);
        postId = jdbcTemplate.queryForObject("SELECT id FROM post ORDER BY id DESC LIMIT 1", Long.class);
        // 一条评论，用于测多态评论点赞
        jdbcTemplate.update(
                "INSERT INTO comment(post_id,author_user_id,root_comment_id,parent_comment_id,reply_to_comment_id,"
                        + "depth,content,status,like_count,version,created_at,updated_at) "
                        + "VALUES (?,?,NULL,NULL,NULL,1,'测试评论','VISIBLE',0,0,?,?)",
                postId, ownerId, now, now);
        commentId = jdbcTemplate.queryForObject("SELECT id FROM comment ORDER BY id DESC LIMIT 1", Long.class);
    }

    private MockHttpSession loginAs(String name) throws Exception {
        return AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder,
                name + "-" + suffix, name + "-" + suffix + "@test.local");
    }

    private MvcResult toggle(MockHttpSession s, String body, String path) throws Exception {
        return mockMvc.perform(post(path).session(s).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult reactionState(MockHttpSession s, String targetType, long targetId) throws Exception {
        return mockMvc.perform(get("/api/v1/web/reactions/state").session(s)
                        .param("targetType", targetType)
                        .param("targetId", String.valueOf(targetId)))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult collectionState(MockHttpSession s, long postId) throws Exception {
        return mockMvc.perform(get("/api/v1/web/collections/state").session(s)
                        .param("postId", String.valueOf(postId)))
                .andExpect(status().isOk()).andReturn();
    }

    private boolean active(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("active").asBoolean();
    }

    private long count(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).get("count").asLong();
    }

    @Test
    void reactionToggleAddThenRemove() throws Exception {
        MockHttpSession alice = loginAs("alice");
        // 第一次点 → 加赞：active=true, count=1
        MvcResult r1 = toggle(alice, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r1)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count(r1)).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(
                jdbcTemplate.queryForObject("SELECT like_count FROM post WHERE id=?", Long.class, postId)).isEqualTo(1L);

        // 再点 → 取消：active=false, count=0
        MvcResult r2 = toggle(alice, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r2)).isFalse();
        org.assertj.core.api.Assertions.assertThat(count(r2)).isEqualTo(0L);
    }

    @Test
    void reactionCountClampedToZeroNeverNegative() throws Exception {
        // GREATEST(0, count-1) 防负验证：造"reaction 记录在、但 like_count 被外部清成 0"的不一致状态，
        // 用户取消赞 → 走 delete 分支 → bump(-1) → like_count = GREATEST(0, 0-1) = GREATEST(0,-1) = 0。
        // 没有 GREATEST 就会变 -1（丑数据）。这里断言结果 ≥ 0，证明钳位生效。
        MockHttpSession alice = loginAs("alice");
        toggle(alice, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle"); // alice 加 → count=1
        // 制造不一致：保留 alice 的 reaction 记录，但手动把 like_count 改成 0
        jdbcTemplate.update("UPDATE post SET like_count=0 WHERE id=?", postId);
        // alice 再点 toggle → insertIgnore=0（已赞记录在）→ 走取消分支 → bump(-1)
        MvcResult r2 = toggle(alice, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r2)).isFalse();
        // GREATEST 钳位：DB 里 like_count = 0，不是 -1
        Long dbCount = jdbcTemplate.queryForObject("SELECT like_count FROM post WHERE id=?", Long.class, postId);
        org.assertj.core.api.Assertions.assertThat(dbCount).isNotNegative();
        org.assertj.core.api.Assertions.assertThat(count(r2)).isEqualTo(dbCount); // 回吐 count 也一致
    }

    @Test
    void collectionToggleAddThenRemove() throws Exception {
        MockHttpSession alice = loginAs("alice");
        MvcResult r1 = toggle(alice, "{\"postId\":" + postId + "}", "/api/v1/web/collections/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r1)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count(r1)).isEqualTo(1L);

        MvcResult r2 = toggle(alice, "{\"postId\":" + postId + "}", "/api/v1/web/collections/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r2)).isFalse();
        org.assertj.core.api.Assertions.assertThat(count(r2)).isEqualTo(0L);

        Long dbCount = jdbcTemplate.queryForObject("SELECT collection_count FROM post WHERE id=?", Long.class, postId);
        org.assertj.core.api.Assertions.assertThat(dbCount).isEqualTo(0L);
    }

    @Test
    void stateEndpointsReturnCurrentUserActiveStateAndGlobalCount() throws Exception {
        MockHttpSession alice = loginAs("alice");
        MockHttpSession bob = loginAs("bob");

        toggle(alice, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle");
        toggle(bob, "{\"targetType\":\"POST\",\"targetId\":" + postId + "}", "/api/v1/web/reactions/toggle");
        toggle(alice, "{\"postId\":" + postId + "}", "/api/v1/web/collections/toggle");

        MvcResult aliceReaction = reactionState(alice, "POST", postId);
        org.assertj.core.api.Assertions.assertThat(active(aliceReaction)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count(aliceReaction)).isEqualTo(2L);

        MvcResult aliceCollection = collectionState(alice, postId);
        org.assertj.core.api.Assertions.assertThat(active(aliceCollection)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count(aliceCollection)).isEqualTo(1L);

        MvcResult bobCollection = collectionState(bob, postId);
        org.assertj.core.api.Assertions.assertThat(active(bobCollection)).isFalse();
        org.assertj.core.api.Assertions.assertThat(count(bobCollection)).isEqualTo(1L);
    }

    @Test
    void reactionOnCommentIsPolymorphic() throws Exception {
        // 点赞评论（多态 targetType=COMMENT）：给 comment.like_count 增减，不影响 post.like_count
        MockHttpSession alice = loginAs("alice");
        MvcResult r = toggle(alice, "{\"targetType\":\"COMMENT\",\"targetId\":" + commentId + "}", "/api/v1/web/reactions/toggle");
        org.assertj.core.api.Assertions.assertThat(active(r)).isTrue();
        org.assertj.core.api.Assertions.assertThat(count(r)).isEqualTo(1L);

        Long commentLikes = jdbcTemplate.queryForObject("SELECT like_count FROM comment WHERE id=?", Long.class, commentId);
        org.assertj.core.api.Assertions.assertThat(commentLikes).isEqualTo(1L);
        // 帖子的 like_count 没被这条评论点赞影响
        Long postLikes = jdbcTemplate.queryForObject("SELECT like_count FROM post WHERE id=?", Long.class, postId);
        org.assertj.core.api.Assertions.assertThat(postLikes).isEqualTo(0L);
    }

    @Test
    void anonymousCannotReact() throws Exception {
        mockMvc.perform(post("/api/v1/web/reactions/toggle")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"targetType\":\"POST\",\"targetId\":" + postId + "}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/web/collections/toggle")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"postId\":" + postId + "}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/web/reactions/state")
                        .param("targetType", "POST")
                        .param("targetId", String.valueOf(postId)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/web/collections/state")
                        .param("postId", String.valueOf(postId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void toggleOnNonexistentPostIs404() throws Exception {
        MockHttpSession alice = loginAs("alice");
        mockMvc.perform(post("/api/v1/web/reactions/toggle")
                        .session(alice).with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("{\"targetType\":\"POST\",\"targetId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("POST_NOT_FOUND_REACTION"));
    }
}
