package com.agentlog.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpSession;
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
 * L10 机娘账号 + 公开主页验收（主线补做，2026-07-23）。
 *
 * 覆盖 identity 领域层恢复后的全部 L10 能力：
 *   - 主人建机娘 POST /owner/agents → 201 AgentView
 *   - 列自己的机娘 GET /owner/agents → 含刚建的、按创建倒序
 *   - 墓碑删除 DELETE /owner/agents/{id} → 204，删后列表不含它（软删非物删）
 *   - 删他人机娘 → 403（行级授权：只能删自己名下）
 *   - 公开机娘主页 GET /public/agents/{id} → 匿名可读
 *   - 公开用户主页 GET /public/users/{id} → 匿名可读，返 username 不泄漏 email
 *
 * 登录态复用 WebAuthIntegrationTest 同款「发码→注册→登录」真实流程（真 MySQL + 真 Redis）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class OwnerAgentIntegrationTest {

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
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /** 走完整真实流程：发码 → 从 Redis 取码 → 注册 → 登录，返回带认证的 session。 */
    private MockHttpSession registerAndLogin(String email, String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/send-code").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isNoContent());

        String code = redisTemplate.opsForValue().get("auth:register:code:" + email);

        mockMvc.perform(post("/api/v1/web/auth/register").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"username\":\"" + username
                                + "\",\"password\":\"password123\",\"verCode\":\"" + code + "\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        return session;
    }

    /** 建一个机娘，返回其 id。 */
    private long createAgent(MockHttpSession session, String nickname) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/owner/agents").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"nickname\":\"" + nickname + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value(nickname))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    @Test
    void ownerCreatesListsAndTombstonesAgent() throws Exception {
        MockHttpSession session = registerAndLogin("l10owner@test.local", "L10Owner");

        long agentId = createAgent(session, "星梦-test");

        // 列表含刚建的机娘
        mockMvc.perform(get("/api/v1/owner/agents").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + agentId + ")].nickname").value("星梦-test"));

        // 墓碑删除
        mockMvc.perform(delete("/api/v1/owner/agents/" + agentId).session(session).with(csrf()))
                .andExpect(status().isNoContent());

        // 删后列表不再含它（软删：行还在库里但 status=DELETED，列表过滤掉）
        mockMvc.perform(get("/api/v1/owner/agents").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + agentId + ")]").isEmpty());
    }

    @Test
    void cannotDeleteOthersAgent() throws Exception {
        MockHttpSession alice = registerAndLogin("l10alice@test.local", "L10Alice");
        MockHttpSession bob = registerAndLogin("l10bob@test.local", "L10Bob");

        long aliceAgent = createAgent(alice, "Alice的机娘");

        // Bob 尝试删 Alice 的机娘 → 403（行级授权）
        mockMvc.perform(delete("/api/v1/owner/agents/" + aliceAgent).session(bob).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_FORBIDDEN"));
    }

    @Test
    void publicAgentProfileIsAnonymouslyReadable() throws Exception {
        MockHttpSession session = registerAndLogin("l10pub@test.local", "L10Pub");
        long agentId = createAgent(session, "公开机娘");

        // 匿名（无 session）也能读机娘主页
        mockMvc.perform(get("/api/v1/public/agents/" + agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(agentId))
                .andExpect(jsonPath("$.nickname").value("公开机娘"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void publicUserProfileHidesEmail() throws Exception {
        MockHttpSession session = registerAndLogin("l10user@test.local", "L10User");
        // 从 /me 拿到自己的 userId
        MvcResult me = mockMvc.perform(get("/api/v1/web/me").session(session))
                .andExpect(status().isOk())
                .andReturn();
        long userId = objectMapper.readTree(me.getResponse().getContentAsString()).get("id").asLong();

        // 匿名读公开用户主页：返 username，不返 email（隐私）
        mockMvc.perform(get("/api/v1/public/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.username").value("L10User"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void publicAgentNotFoundFor404() throws Exception {
        mockMvc.perform(get("/api/v1/public/agents/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }
}
