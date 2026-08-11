package com.agentlog.identity.pairing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentlog.support.AuthTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * L13 阶段③·auth refresh 令牌轮换验收（RTR + 盗用连坐吊销，ADR-0002）。
 *
 * 验收映射：
 *   refreshRotatesTokens       → 拿 refresh 换新 access+refresh；新 access 可用、旧 access 因旧会话 REVOKED 立即失效
 *   refreshWithBogusToken      → 假 refresh → 401 REFRESH_TOKEN_INVALID
 *   refreshWithExpiredRefresh  → refresh 过期 → 401 REFRESH_TOKEN_EXPIRED
 *   reusedRefreshRevokesFamily → 重放已轮换的 refresh（疑似盗用）→ 401 + 连坐吊销该 installation 名下所有会话
 *
 * 黑盒：token 走真实配对流铸出；过期用 jdbcTemplate 改 refresh_expires_at 模拟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CliTokenRefreshIntegrationTest {

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
    void refreshRotatesTokens() throws Exception {
        String[] minted = mintTokens("inst-rot", "rotowner", "rotowner@example.com");
        String access0 = minted[0];
        String refresh0 = minted[1];

        MvcResult res = mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refresh0 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerAccessToken").exists())
                .andExpect(jsonPath("$.ownerRefreshToken").exists())
                .andReturn();
        JsonNode rotated = objectMapper.readTree(res.getResponse().getContentAsString());
        String access1 = rotated.get("ownerAccessToken").asText();
        Assertions.assertThat(access1).isNotEqualTo(access0);

        // 新 access 可用。
        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + access1))
                .andExpect(status().isOk());
        // 旧 access 因旧会话被 REVOKED 而立即失效（RTR 副作用）。
        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + access0))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OWNER_TOKEN_INVALID"));
    }

    @Test
    void refreshWithBogusToken() throws Exception {
        mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"owner_rt_bogus\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    void refreshWithExpiredRefresh() throws Exception {
        String[] minted = mintTokens("inst-rexp", "rexpowner", "rexpowner@example.com");
        jdbcTemplate.update("UPDATE owner_access_session SET refresh_expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE installation_id=(SELECT id FROM client_installation WHERE installation_code='inst-rexp')");

        mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + minted[1] + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
    }

    @Test
    void reusedRefreshRevokesFamily() throws Exception {
        String[] minted = mintTokens("inst-theft", "theftowner", "theftowner@example.com");
        String refresh0 = minted[1];

        // 第一次轮换 → 得 access1，旧会话 REVOKED。
        MvcResult res = mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refresh0 + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String access1 = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("ownerAccessToken").asText();
        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + access1))
                .andExpect(status().isOk());

        // 重放已轮换的 refresh0（疑似盗用）→ 失败。
        mockMvc.perform(post("/api/v1/cli/auth/refresh")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"" + refresh0 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        // 连坐：该设备名下的 access1 也被吊销（盗用响应）。
        mockMvc.perform(get("/api/v1/cli/whoami").header("Authorization", "Bearer " + access1))
                .andExpect(status().isUnauthorized());
    }

    /** 跑一遍真实设备授权流，返回 APPROVED 后的 {明文 access, 明文 refresh}。 */
    private String[] mintTokens(String installationCode, String username, String email) throws Exception {
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
        JsonNode approved = objectMapper.readTree(tokenResult.getResponse().getContentAsString());
        return new String[] {approved.get("ownerAccessToken").asText(), approved.get("ownerRefreshToken").asText()};
    }
}
