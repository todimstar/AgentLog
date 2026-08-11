package com.agentlog.identity.pairing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
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
 * L13 阶段④·Chain 3（机娘代入）验收：owner 令牌 assume 出 AgentActingToken，Chain 3 消费之。ADR-0003。
 *
 * 试金石端点 GET /api/v1/agent/whoami（Chain 3 首个受保护消费者）。
 * 验收映射（ADR-0003）：
 *   assumeThenAgentWhoami   → owner 代入自己名下机娘 → 200 拿机娘令牌 → /agent/whoami 返回该机娘身份 + tool/run
 *   assumeForeignAgentDenied→ 代入别人名下的机娘 → 404 AGENT_NOT_FOUND（不泄漏他人机娘存在性）
 *   eachRunIndependentToken → 同机娘不同 clientRunId 各换一把 → 两把令牌不同、各自独立会话
 *   agentWhoamiWithoutToken → 无令牌进 /agent/** → 401
 *   agentWhoamiBogusToken   → 假机娘令牌 → 401 AGENT_TOKEN_INVALID（RE_ASSUME）
 *   agentTokenRevokedOnTheft→ 设备 refresh 被重放盗用 → 连坐：该设备机娘令牌一并失效（401）
 *
 * 黑盒：owner 令牌走真实配对流铸出；agent_account 直插（属别模块、尚无 Java 领域层）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AgentAssumeIntegrationTest {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void assumeThenAgentWhoami() throws Exception {
        Minted m = mintOwnerToken("inst-asm", "asmowner", "asmowner@example.com");
        Long agentId = insertAgent(m.ownerUserId, "小助");

        // owner 代入自己的机娘 → 拿 AgentActingToken。
        MvcResult assumeResult = mockMvc.perform(post("/api/v1/cli/agents/" + agentId + "/assume")
                        .header("Authorization", "Bearer " + m.ownerAccessToken)
                        .contentType("application/json")
                        .content("{\"sourceTool\":\"claude-code\",\"clientRunId\":\"run-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentActingToken").exists())
                .andExpect(jsonPath("$.agentAccountId").value(agentId.intValue()))
                .andReturn();
        String agentToken = objectMapper.readTree(assumeResult.getResponse().getContentAsString())
                .get("agentActingToken").asText();

        // Chain 3：机娘令牌进 /agent/whoami → 返回机娘身份 + 本次运行标识。
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentAccountId").value(agentId.intValue()))
                .andExpect(jsonPath("$.ownerUserId").value(m.ownerUserId.intValue()))
                .andExpect(jsonPath("$.sourceTool").value("claude-code"))
                .andExpect(jsonPath("$.clientRunId").value("run-1"));
    }

    @Test
    void assumeForeignAgentDenied() throws Exception {
        Minted m = mintOwnerToken("inst-foreign", "foreignowner", "foreignowner@example.com");
        // 另一个主人的机娘。
        Long strangerId = insertUser("stranger", "stranger@example.com");
        Long foreignAgent = insertAgent(strangerId, "别人的娘");

        mockMvc.perform(post("/api/v1/cli/agents/" + foreignAgent + "/assume")
                        .header("Authorization", "Bearer " + m.ownerAccessToken)
                        .contentType("application/json")
                        .content("{\"sourceTool\":\"claude-code\",\"clientRunId\":\"run-x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
    }

    @Test
    void eachRunIndependentToken() throws Exception {
        Minted m = mintOwnerToken("inst-runs", "runsowner", "runsowner@example.com");
        Long agentId = insertAgent(m.ownerUserId, "多面手");

        String t1 = assume(m.ownerAccessToken, agentId, "claude-code", "run-A");
        String t2 = assume(m.ownerAccessToken, agentId, "claude-code", "run-B");

        // 每次运行独立：两把令牌不同。
        Assertions.assertThat(t1).isNotEqualTo(t2);
        // 两条独立会话行。
        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_acting_session WHERE agent_account_id=? AND status='ACTIVE'",
                Integer.class, agentId);
        Assertions.assertThat(sessionCount).isEqualTo(2);
        // 两把都能各自认。
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer " + t1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientRunId").value("run-A"));
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer " + t2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientRunId").value("run-B"));
    }

    @Test
    void agentWhoamiWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/agent/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void agentWhoamiBogusToken() throws Exception {
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer agent_at_bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_TOKEN_INVALID"));
    }

    @Test
    void agentTokenRevokedOnTheft() throws Exception {
        Minted m = mintOwnerToken("inst-theft", "theftowner", "theftowner@example.com");
        Long agentId = insertAgent(m.ownerUserId, "受害娘");
        String agentToken = assume(m.ownerAccessToken, agentId, "claude-code", "run-victim");

        // 先 refresh 一次（原 refresh 变为已轮换）。
        MvcResult r1 = mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + m.ownerRefreshToken + "\"}"))
                .andExpect(status().isOk()).andReturn();
        // 机娘令牌此刻仍有效。
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());

        // 攻击者重放已轮换的旧 refresh → 盗用信号 → 连坐吊销该设备名下所有会话（含机娘）。
        mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + m.ownerRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        // 机娘令牌被连坐吊销 → 401。
        mockMvc.perform(get("/api/v1/agent/whoami").header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AGENT_TOKEN_INVALID"));
    }

    // —— 辅助 ——

    private String assume(String ownerToken, Long agentId, String tool, String runId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cli/agents/" + agentId + "/assume")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType("application/json")
                        .content("{\"sourceTool\":\"" + tool + "\",\"clientRunId\":\"" + runId + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("agentActingToken").asText();
    }

    private Long insertUser(String username, String email) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,?,'ACTIVE',?,?)",
                username, email, passwordEncoder.encode("password123"), now, now);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM user_account WHERE username=?", Long.class, username);
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

    /** 跑真实设备授权流，返回明文 owner access + refresh token 及 ownerUserId。 */
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
        return new Minted(tok.get("ownerAccessToken").asText(),
                tok.get("ownerRefreshToken").asText(), ownerUserId);
    }

    private record Minted(String ownerAccessToken, String ownerRefreshToken, Long ownerUserId) {
    }
}
