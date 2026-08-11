package com.agentlog.identity.pairing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * L13 阶段②·Chain 2（CLI Bearer 认证链）验收：消费 L12 铸的 OwnerAccessToken。
 *
 * 试金石端点 GET /api/v1/cli/whoami（Chain 2 首个受保护消费者，兼作 CLI auth status 后端）。
 * 验收映射（ADR-0001）：
 *   whoamiWithValidToken        → 真配对流铸的 token 当 Bearer → 200 + owner/installation 对上库
 *   whoamiWithoutToken          → 无 Authorization → 401（通用未认证，"你都没带令牌"）
 *   whoamiWithBogusToken        → 假 token（digest 无命中）→ 401 OWNER_TOKEN_INVALID（去重新配对）
 *   whoamiWithExpiredToken      → access 过期 → 401 OWNER_TOKEN_EXPIRED（去 refresh，带 recoveryActions）
 *   whoamiWithRevokedToken      → status=REVOKED → 401 OWNER_TOKEN_INVALID
 *   pairingEndpointsStillAnonymous → Chain 2 接管 /cli/** 后，配对两端点仍 permitAll（回归守卫）
 *
 * 黑盒：token 走真实配对流铸出（不手插库），过期/吊销用 jdbcTemplate 改态模拟。
 * 真 MySQL + 真 Redis（Testcontainers）；确认态登录复用 AuthTestSupport（email 登录经 LoginAttemptService 需 Redis）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CliBearerAuthIntegrationTest {

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
    void whoamiWithValidToken() throws Exception {
        String token = mintOwnerToken("inst-who", "whoowner", "whoowner@example.com");

        Long installationId = jdbcTemplate.queryForObject(
                "SELECT id FROM client_installation WHERE installation_code='inst-who'", Long.class);
        Long ownerUserId = jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM owner_access_session WHERE installation_id=?", Long.class, installationId);

        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerUserId").value(ownerUserId.intValue()))
                .andExpect(jsonPath("$.installationId").value(installationId.intValue()));
    }

    @Test
    void whoamiWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/cli/whoami"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void whoamiWithBogusToken() throws Exception {
        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer owner_at_bogus"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OWNER_TOKEN_INVALID"));
    }

    @Test
    void whoamiWithExpiredToken() throws Exception {
        String token = mintOwnerToken("inst-exp2", "expowner", "expowner@example.com");
        // 把 access 过期时间改到过去（模拟超时）。
        jdbcTemplate.update("UPDATE owner_access_session SET access_expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE installation_id=(SELECT id FROM client_installation WHERE installation_code='inst-exp2')");

        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OWNER_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.recoveryActions").isArray());
    }

    @Test
    void whoamiWithRevokedToken() throws Exception {
        String token = mintOwnerToken("inst-rev", "revowner", "revowner@example.com");
        jdbcTemplate.update("UPDATE owner_access_session SET status='REVOKED' "
                + "WHERE installation_id=(SELECT id FROM client_installation WHERE installation_code='inst-rev')");

        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OWNER_TOKEN_INVALID"));
    }

    @Test
    void pairingEndpointsStillAnonymous() throws Exception {
        // Chain 2 接管 /cli/** 后，配对发起仍匿名可达（换令牌不能要令牌）。
        mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-anon\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated());
    }

    /** 跑一遍真实设备授权流，返回 APPROVED 后的明文 ownerAccessToken。 */
    private String mintOwnerToken(String installationCode, String username, String email) throws Exception {
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
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn();
        return objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("ownerAccessToken").asText();
    }
}
