package com.agentlog.forum;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * L10 Feed 作者头像组验收。
 *
 * 重点不是测用户模块，而是证明 forum Feed 读模型能把 post.owner_user_id
 * 批量翻译成前端可渲染的 AuthorView，卡片不再只能显示"佚名"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class FeedIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static int seq = 0;
    private Long channelId;
    private Long ownerId;

    @BeforeEach
    void seedFeedPost() {
        String suffix = "f" + (++seq);
        Instant now = Instant.now();

        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)",
                "feed-" + suffix, "L10 作者分区", now, now);
        channelId = jdbcTemplate.queryForObject("SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO user_account(username,password_hash,display_name,avatar_media_public_id,status,created_at,updated_at) "
                        + "VALUES (?,'x','作者Alice','avatar-alice','ACTIVE',?,?)",
                "feed-owner-" + suffix, now, now);
        ownerId = jdbcTemplate.queryForObject("SELECT id FROM user_account ORDER BY id DESC LIMIT 1", Long.class);

        jdbcTemplate.update(
                "INSERT INTO post(owner_user_id,channel_id,visibility_status,title_cache,summary_cache,content_origin_cache,"
                        + "comment_count,hot_score,is_pinned,is_essence,iteration_count,view_count,like_count,collection_count,"
                        + "version,published_at,created_at,updated_at) "
                        + "VALUES (?,?,'PUBLISHED','L10 作者头像组','Feed 卡片应该展示真实作者','HUMAN_ONLY',"
                        + "0,0,TRUE,FALSE,1,3,0,0,0,?,?,?)",
                ownerId, channelId, now, now, now);
    }

    @Test
    void feedCardsReturnOwnerAuthorProjection() throws Exception {
        mockMvc.perform(get("/api/v1/public/posts")
                        .param("channelId", String.valueOf(channelId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("L10 作者头像组"))
                .andExpect(jsonPath("$.items[0].authors.length()").value(1))
                .andExpect(jsonPath("$.items[0].authors[0].authorType").value("OWNER"))
                .andExpect(jsonPath("$.items[0].authors[0].userId").value(ownerId))
                .andExpect(jsonPath("$.items[0].authors[0].displayName").value("作者Alice"))
                .andExpect(jsonPath("$.items[0].authors[0].avatarMediaId").value("avatar-alice"))
                .andExpect(jsonPath("$.items[0].authors[0].deleted").value(false));
    }
}
