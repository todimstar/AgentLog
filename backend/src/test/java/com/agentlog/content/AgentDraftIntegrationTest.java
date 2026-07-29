package com.agentlog.content;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L14 单机娘 Skill 自动投稿验收。content 模块 Chain 3 消费第一课。
 *
 * 验收映射（ADR-0004 / course_schedule）：
 *   agentSubmitsDraft        → 机娘 assume 后投草稿 201，库里 contribution.author_type=AGENT + agent 维度字段对上
 *   draftBelongsToOwner      → draft.owner_user_id = 机娘背后的主人，该主人的 /owner/drafts/{id} 能读到
 *   draftUrlPointsToPreview  → 返回 draftUrl 含 base-url + draftId（主人审稿预览路径）
 *   submitWithoutAgentToken  → 无令牌 → 401
 *   ownerTokenRejectedOnAgentChain → owner 令牌打 /agent/drafts → 401（Chain 3 只认 agent 令牌，链隔离）
 *   agentHasNoPublish        → 机娘无 publish 能力（/agent/drafts/{id}/publish 不存在）
 *
 * 黑盒：owner 令牌走真实配对流铸出（mintOwnerToken），再 assume 出 agent 令牌；agent_account/channel 直插。
 * 铸令牌套路对齐 AgentAssumeIntegrationTest（L13）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AgentDraftIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry reg) {
        reg.add("spring.data.redis.host", redis::getHost);
        reg.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private static int seq = 0;

    @Test
    void agentSubmitsDraft() throws Exception {
        Fixture f = setup("sub");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-1");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"机娘的开发日志\",\"channelId\":" + f.channelId
                                + ",\"content\":\"今天实现了 Chain 3 投稿\",\"summary\":\"L14\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.draft.draftId").exists())
                .andExpect(jsonPath("$.draftUrl").exists())
                .andReturn();

        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        long draftId = body.get("draft").get("draftId").asLong();

        // 库里 contribution 忠实落 AGENT 作者维度。
        Map<String, Object> contrib = jdbcTemplate.queryForMap(
                "SELECT c.author_type, c.author_agent_id, c.source_tool, c.client_run_id "
                        + "FROM contribution c "
                        + "JOIN draft_block b ON b.contribution_id = c.id "
                        + "WHERE b.draft_id = ?", draftId);
        Assertions.assertThat(contrib.get("author_type")).isEqualTo("AGENT");
        Assertions.assertThat(((Number) contrib.get("author_agent_id")).longValue()).isEqualTo(f.agentId);
        Assertions.assertThat(contrib.get("source_tool")).isEqualTo("claude-code");
        Assertions.assertThat(contrib.get("client_run_id")).isEqualTo("run-1");
    }

    @Test
    void draftBelongsToOwner() throws Exception {
        Fixture f = setup("own");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-own");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"归属测试\",\"channelId\":" + f.channelId
                                + ",\"content\":\"正文\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long draftId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("draft").get("draftId").asLong();

        // 草稿归背后的主人。
        Long ownerOfDraft = jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM draft WHERE id=?", Long.class, draftId);
        Assertions.assertThat(ownerOfDraft).isEqualTo(f.ownerUserId);

        // 该主人登录 web 后能读到这篇草稿（/owner/drafts/{id} 走 session）。
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + f.ownerEmail + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/owner/drafts/" + draftId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").value((int) draftId));
    }

    @Test
    void draftUrlPointsToPreview() throws Exception {
        Fixture f = setup("url");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-url");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"URL 测试\",\"channelId\":" + f.channelId
                                + ",\"content\":\"正文\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
        long draftId = body.get("draft").get("draftId").asLong();
        String draftUrl = body.get("draftUrl").asText();

        // 草稿 URL 指向主人审稿预览路径，含 draftId。
        Assertions.assertThat(draftUrl).contains("/owner/drafts/" + draftId);
    }

    @Test
    void submitWithoutAgentToken() throws Exception {
        Fixture f = setup("noauth");
        mockMvc.perform(post("/api/v1/agent/drafts")
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"channelId\":" + f.channelId + ",\"content\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerTokenRejectedOnAgentChain() throws Exception {
        Fixture f = setup("chainiso");
        // owner 令牌（非 agent 令牌）打 /agent/** → Chain 3 拒绝。
        mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + f.ownerAccessToken)
                        .contentType("application/json")
                        .content("{\"title\":\"x\",\"channelId\":" + f.channelId + ",\"content\":\"y\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentHasNoPublish() throws Exception {
        Fixture f = setup("nopub");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-nopub");
        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"不能发布\",\"channelId\":" + f.channelId
                                + ",\"content\":\"正文\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long draftId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("draft").get("draftId").asLong();

        // 机娘无 publish 能力：/agent 下没有 publish 端点（404，路由不存在）。
        mockMvc.perform(post("/api/v1/agent/drafts/" + draftId + "/publish")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"expectedDraftVersion\":0}"))
                .andExpect(status().isNotFound());
    }

    /**
     * 主人发布机娘投的稿后，版本快照必须完整保留【机娘身份】。
     *
     * 由来（L14 停靠点2 端到端实测抓到的回归）：publish 的复制块循环写于 L06，当时只有 OWNER 作者，
     * agent 维度恒为 null 故未复制。L14 激活 AGENT 投稿后，这个遗漏会产出【自相矛盾的快照】——
     * author_type=AGENT 却 author_agent_id=NULL / source_tool=NULL，追溯链在发布这一步断掉。
     */
    @Test
    void publishPreservesAgentAuthorship() throws Exception {
        Fixture f = setup("pubagent");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-pub");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"发布保真\",\"channelId\":" + f.channelId
                                + ",\"content\":\"机娘写的正文\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long draftId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("draft").get("draftId").asLong();

        // 主人（且只有主人）能发布：web session 登录后调 /owner/drafts/{id}/publish。
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + f.ownerEmail + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/owner/drafts/" + draftId + "/publish").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"expectedDraftVersion\":0,\"publishMode\":\"AUTO_IF_ALLOWED\"}"))
                .andExpect(status().isOk());

        // 版本快照的作者维度要与草稿块一致——四个字段一个都不能丢。
        Map<String, Object> vb = jdbcTemplate.queryForMap(
                "SELECT vb.author_type, vb.author_user_id, vb.author_agent_id, vb.source_tool "
                        + "FROM post_version_block vb "
                        + "JOIN draft_block b ON vb.source_draft_block_id = b.id "
                        + "WHERE b.draft_id = ?", draftId);
        Assertions.assertThat(vb.get("author_type")).isEqualTo("AGENT");
        Assertions.assertThat(vb.get("author_user_id")).isNull();
        Assertions.assertThat(((Number) vb.get("author_agent_id")).longValue()).isEqualTo(f.agentId);
        Assertions.assertThat(vb.get("source_tool")).isEqualTo("claude-code");
    }

    /**
     * 草稿块要带【作者视图】：主人审稿时必须看得出是哪个机娘写的，不能只有 sourceTool。
     *
     * 由来：契约 ContentBlockView.author 早已声明，但两个模块的后端都没填（独立 fix 补齐）。
     * 机娘的 nickname 落进 AuthorView.username 位——契约里这个位是"展示名"，不区分人/机娘。
     */
    @Test
    void draftBlockCarriesAgentAuthor() throws Exception {
        Fixture f = setup("blockauthor");
        String agentToken = assume(f.ownerAccessToken, f.agentId, "claude-code", "run-author");

        MvcResult res = mockMvc.perform(post("/api/v1/agent/drafts")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType("application/json")
                        .content("{\"title\":\"作者视图\",\"channelId\":" + f.channelId
                                + ",\"content\":\"机娘写的\"}"))
                .andExpect(status().isCreated())
                // 建草稿的响应里就该带作者（不必等主人再查一次）。
                .andExpect(jsonPath("$.draft.blocks[0].author.authorType").value("AGENT"))
                .andExpect(jsonPath("$.draft.blocks[0].author.agentId").value(f.agentId.intValue()))
                .andExpect(jsonPath("$.draft.blocks[0].author.deleted").value(false))
                .andReturn();
        long draftId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("draft").get("draftId").asLong();

        // 主人查草稿详情，同样要带作者，且展示名是机娘昵称。
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + f.ownerEmail + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/owner/drafts/" + draftId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].author.authorType").value("AGENT"))
                .andExpect(jsonPath("$.blocks[0].author.agentId").value(f.agentId.intValue()))
                .andExpect(jsonPath("$.blocks[0].author.username").value(f.agentNickname))
                .andExpect(jsonPath("$.blocks[0].sourceTool").value("claude-code"));
    }

    /** 主人自己投的草稿，块作者是 OWNER + 用户名——与机娘路径形成对照，防止只顾 AGENT 把 OWNER 改回归了。 */
    @Test
    void ownerDraftBlockCarriesOwnerAuthor() throws Exception {
        Fixture f = setup("ownauthor");
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + f.ownerEmail + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/owner/drafts").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"主人写的\",\"channelId\":" + f.channelId
                                + ",\"content\":\"正文\",\"declaredExternalAiContent\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocks[0].author.authorType").value("OWNER"))
                .andExpect(jsonPath("$.blocks[0].author.userId").value(f.ownerUserId.intValue()))
                .andExpect(jsonPath("$.blocks[0].author.agentId").doesNotExist())
                .andExpect(jsonPath("$.blocks[0].sourceTool").doesNotExist());
    }

    // —— 辅助 ——

    /** 建 channel + 铸 owner 令牌 + 建机娘，打包成一次测试的夹具。 */
    private Fixture setup(String tag) throws Exception {
        String suffix = tag + (++seq);
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO forum_channel(slug,name,sort_order,enabled,post_count,created_at,updated_at) "
                        + "VALUES (?,?,0,TRUE,0,?,?)", "ch-" + suffix, "开发日志", now, now);
        Long channelId = jdbcTemplate.queryForObject(
                "SELECT id FROM forum_channel ORDER BY id DESC LIMIT 1", Long.class);

        String email = "owner-" + suffix + "@example.com";
        Minted m = mintOwnerToken("inst-" + suffix, "owner-" + suffix, email);
        String nickname = "小助-" + suffix;
        Long agentId = insertAgent(m.ownerUserId, nickname);
        return new Fixture(m.ownerAccessToken, m.ownerUserId, email, agentId, nickname, channelId);
    }

    private String assume(String ownerToken, Long agentId, String tool, String runId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cli/agents/" + agentId + "/assume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("{\"sourceTool\":\"" + tool + "\",\"clientRunId\":\"" + runId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("agentActingToken").asText();
    }

    private Long insertAgent(Long ownerUserId, String nickname) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO agent_account(owner_user_id,nickname,status,created_at,updated_at) "
                        + "VALUES (?,?,'ACTIVE',?,?)",
                ownerUserId, nickname, now, now);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM agent_account WHERE owner_user_id=? AND nickname=?",
                Long.class, ownerUserId, nickname);
    }

    /** 跑真实设备授权流，返回明文 owner access token 及 ownerUserId。 */
    private Minted mintOwnerToken(String installationCode, String username, String email) throws Exception {
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"" + installationCode + "\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode pair = objectMapper.readTree(pairResult.getResponse().getContentAsString());
        String deviceCode = pair.get("deviceCode").asText();
        String userCode = pair.get("userCode").asText();

        MockHttpSession session =
                AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder, username, email);
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"" + userCode + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED")).andReturn();
        JsonNode tok = objectMapper.readTree(tokenResult.getResponse().getContentAsString());

        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE email=?", Long.class, email);
        return new Minted(tok.get("ownerAccessToken").asText(), ownerUserId);
    }

    private record Minted(String ownerAccessToken, Long ownerUserId) {
    }

    private record Fixture(String ownerAccessToken, Long ownerUserId, String ownerEmail,
                           Long agentId, String agentNickname, Long channelId) {
    }
}
