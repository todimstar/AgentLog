package com.agentlog.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * L11.5 邮箱登录验收：发验证码 → 注册（校验码）→ email 登录 → 带 Session 访问 /me（刷新仍登录）；
 * 无 CSRF 的 POST 被拒；错误验证码被拒。
 *
 * 用真 MySQL + 真 Redis（Testcontainers）——注册要把验证码写进 Redis、登录要过失败限流。
 * 测试从 Redis 直接读验证码（真实场景用户从邮箱拿）——这是"测试专用后门"，不走真邮件。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class WebAuthIntegrationTest {

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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username));

        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
        return session;
    }

    @Test
    void registerThenLoginThenStayLoggedInOnRefresh() throws Exception {
        MockHttpSession session = registerAndLogin("authalice@test.local", "AuthAlice");
        // 带着【同一个 session】访问 /me —— 模拟刷新后再请求，应仍是登录态，且展示名是 username。
        mockMvc.perform(get("/api/v1/web/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("AuthAlice"))
                .andExpect(jsonPath("$.email").value("authalice@test.local"));
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/v1/web/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeRequestWithoutCsrfTokenIsForbidden() throws Exception {
        // logout 不在 CSRF 豁免名单，用它验证"CSRF 拦截仍生效"。
        MockHttpSession session = registerAndLogin("authbob@test.local", "AuthBob");
        mockMvc.perform(post("/api/v1/web/auth/logout").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerWithWrongCodeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/web/auth/send-code").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"authcarol@test.local\"}"))
                .andExpect(status().isNoContent());

        String real = redisTemplate.opsForValue().get("auth:register:code:authcarol@test.local");
        String wrong = "000000".equals(real) ? "111111" : "000000";

        mockMvc.perform(post("/api/v1/web/auth/register").with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"authcarol@test.local\",\"username\":\"AuthCarol\","
                                + "\"password\":\"password123\",\"verCode\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_CODE_INVALID"));
    }
}
