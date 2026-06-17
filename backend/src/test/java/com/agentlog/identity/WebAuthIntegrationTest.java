package com.agentlog.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

/**
 * L05 认证验收：注册 → 登录 → 带 Session 访问受保护接口（刷新仍登录）、无 CSRF 的 POST 被拒。
 *
 * 用真 MySQL（Testcontainers）因为注册/登录要查 user_account 验密码；
 * 用 MockMvc 模拟"带 Session Cookie / 带或不带 CSRF token"的请求序列——这是 L05 比 L03 难的点：
 * 验证的是【跨请求的状态保持】和【CSRF 拦截行为】，不是单次查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class WebAuthIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLoginThenStayLoggedInOnRefresh() throws Exception {
        // 用同一个 MockHttpSession 贯穿多个请求 = 模拟浏览器持有同一会话（"刷新仍登录"的载体）。
        // 这是 MockMvc 测 Session 的标准做法：传 session 对象，而非抓 JSESSIONID cookie。
        MockHttpSession session = new MockHttpSession();

        // 1. 注册（POST unsafe，.with(csrf()) 附带合法 CSRF token 才能过 CSRF 关）。
        mockMvc.perform(post("/api/v1/web/auth/register")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"alice","password":"password123","displayName":"Alice"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("alice"));

        // 2. 登录：成功后认证被写进这个 session。
        mockMvc.perform(post("/api/v1/web/auth/login")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"alice","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));

        // 3. 带着【同一个 session】访问 /me —— 模拟刷新后再请求。应仍是登录态。
        mockMvc.perform(get("/api/v1/web/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        // 不带 Session 访问受保护接口 → 401（未认证）。
        mockMvc.perform(get("/api/v1/web/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsafeRequestWithoutCsrfTokenIsForbidden() throws Exception {
        // 验证 CSRF 防护生效：用未豁免的 logout（POST）。
        // 注意：register/login/csrf 三个入口在 L05 被 .ignoringRequestMatchers 豁免了 CSRF
        // （修 CSRF 死锁所需），所以不能再用它们验证——它们本就不要 CSRF。
        // logout 不在豁免名单，是验证"CSRF 拦截仍生效"的合适样本。
        MockHttpSession session = new MockHttpSession();

        // 先注册 + 登录拿到会话（这俩走豁免入口，带不带 csrf 都行，这里带上更贴近真实）。
        mockMvc.perform(post("/api/v1/web/auth/register")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"bob","password":"password123","displayName":"Bob"}"""))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/web/auth/login")
                        .session(session)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"bob","password":"password123"}"""))
                .andExpect(status().isOk());

        // 已登录，但 logout 不带 CSRF token → 403。证明 CSRF 防护对未豁免接口仍生效。
        mockMvc.perform(post("/api/v1/web/auth/logout").session(session))
                .andExpect(status().isForbidden());
    }
}
