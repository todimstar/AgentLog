package com.agentlog.identity.pairing;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
 * L12 设备配对验收：完整 OAuth 设备授权流 + 过期处理 + 一次性 + 授权边界。
 *
 * 验收映射：
 *   fullPairingFlow      → 配对→轮询(PENDING)→浏览器确认→轮询(APPROVED+token)，配对成功（验收项①）
 *   expiredPairingFails  → 配对码过期 → exchange 返回 EXPIRED（验收项②过期处理）
 *   deviceCodeOneShot    → 已 CONSUMED 的 deviceCode 再换 → 409（一次性，防重放）
 *   confirmWrongCode     → 错误 userCode 确认 → 404 PAIRING_NOT_FOUND
 *   confirmRequiresLogin → 匿名确认 → 401（confirm 需登录，currentUserId 从 Session 派生）
 *
 * 真 MySQL + 真 Redis（Testcontainers）——确认态登录复用 AuthTestSupport（email 登录，经 LoginAttemptService 需 Redis）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class DevicePairingIntegrationTest {

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
    void fullPairingFlow() throws Exception {
        // 1) CLI 发起配对（匿名，无 CSRF）。
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-001\",\"deviceName\":\"my-laptop\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceCode").exists())
                .andExpect(jsonPath("$.userCode").exists())
                .andExpect(jsonPath("$.verificationUri").exists())
                .andReturn();
        JsonNode pair = objectMapper.readTree(pairResult.getResponse().getContentAsString());
        String deviceCode = pair.get("deviceCode").asText();
        String userCode = pair.get("userCode").asText();

        // 2) CLI 轮询：还没批准 → PENDING。
        mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 3) 主人浏览器登录后确认 userCode（email 登录）。
        MockHttpSession session =
                AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder, "pairowner", "pairowner@example.com");
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"" + userCode + "\"}"))
                .andExpect(status().isNoContent());

        // 4) CLI 再轮询：已批准 → APPROVED + token。
        mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.ownerAccessToken").exists())
                .andExpect(jsonPath("$.ownerRefreshToken").exists());

        // installation 应已绑定主人 + ACTIVE；owner_access_session 应落一行 ACTIVE。
        String instStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM client_installation WHERE installation_code='inst-001'", String.class);
        Assertions.assertThat(instStatus).isEqualTo("ACTIVE");
        Integer sessionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM owner_access_session s "
                        + "JOIN client_installation i ON s.installation_id=i.id "
                        + "WHERE i.installation_code='inst-001' AND s.status='ACTIVE'", Integer.class);
        Assertions.assertThat(sessionCount).isEqualTo(1);
    }

    @Test
    void expiredPairingFails() throws Exception {
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-exp\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated()).andReturn();
        String deviceCode = objectMapper.readTree(pairResult.getResponse().getContentAsString())
                .get("deviceCode").asText();

        // 手动把过期时间改到过去（模拟超时）。
        jdbcTemplate.update("UPDATE device_pairing_request SET expires_at = DATE_SUB(NOW(3), INTERVAL 1 MINUTE) "
                + "WHERE installation_id = (SELECT id FROM client_installation WHERE installation_code='inst-exp')");

        // 轮询 → EXPIRED（验收项：过期处理）。
        mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void deviceCodeOneShot() throws Exception {
        MvcResult pairResult = mockMvc.perform(post("/api/v1/cli/device-pairings")
                        .contentType("application/json")
                        .content("{\"installationCode\":\"inst-once\",\"deviceName\":\"dev\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode pair = objectMapper.readTree(pairResult.getResponse().getContentAsString());
        String deviceCode = pair.get("deviceCode").asText();
        String userCode = pair.get("userCode").asText();

        MockHttpSession session =
                AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder, "onceowner", "onceowner@example.com");
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"" + userCode + "\"}"))
                .andExpect(status().isNoContent());

        // 第一次换 token → APPROVED。
        mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 第二次用同 deviceCode 换 → 已 CONSUMED → 冲突（防重放）。
        mockMvc.perform(post("/api/v1/cli/device-pairings/token")
                        .contentType("application/json")
                        .content("{\"deviceCode\":\"" + deviceCode + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAIRING_ALREADY_HANDLED"));
    }

    @Test
    void confirmWrongCode() throws Exception {
        MockHttpSession session =
                AuthTestSupport.insertUserAndLogin(mockMvc, jdbcTemplate, passwordEncoder, "wrongowner", "wrongowner@example.com");
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"ZZZZ-ZZZZ\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAIRING_NOT_FOUND"));
    }

    @Test
    void confirmRequiresLogin() throws Exception {
        // 匿名确认（带 CSRF token 过 CSRF 关，但无登录态）→ 401。
        mockMvc.perform(post("/api/v1/web/device-pairings/confirm").with(csrf())
                        .contentType("application/json")
                        .content("{\"userCode\":\"ABCD-EFGH\"}"))
                .andExpect(status().isUnauthorized());
    }
}
