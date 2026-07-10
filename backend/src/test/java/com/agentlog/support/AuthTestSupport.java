package com.agentlog.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 测试夹具：直插一个 ACTIVE 用户并用 email 登录，返回带认证的 session。
 *
 * L11.5 后登录凭据是 email。非 auth 测试（评论/点赞/媒体）聚焦自己的模块，
 * 用这个绕开"发验证码 → 注册"的完整流程（那条链交给 WebAuthIntegrationTest 专测）。
 * 登录仍走真实 /auth/login，所以会经过 LoginAttemptService → 需要 Redis 容器。
 */
public final class AuthTestSupport {

    private AuthTestSupport() {
    }

    public static MockHttpSession insertUserAndLogin(
            MockMvc mockMvc, JdbcTemplate jdbc, PasswordEncoder encoder,
            String username, String email) throws Exception {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO user_account(username,email,password_hash,status,created_at,updated_at) "
                        + "VALUES (?,?,?,'ACTIVE',?,?)",
                username, email, encoder.encode("password123"), now, now);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/web/auth/login").session(session).with(csrf())
                        .contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
        return session;
    }
}
