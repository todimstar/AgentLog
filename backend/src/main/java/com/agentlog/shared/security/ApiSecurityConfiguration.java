package com.agentlog.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * L05：Web Session + CSRF 安全配置。
 *
 * 与旧项目 springBootDemo 的 SecurityConfig 处处相反（核心对照）：
 *   旧项目 JWT：SessionCreationPolicy.STATELESS + csrf.disable() + 自写 JwtAuthenticationFilter
 *   本项目 Session：IF_REQUIRED（有状态）+ 启用 CSRF + 不写过滤器（框架内置复原）
 * 原因见 09-security/security-blueprint.md：同源 SPA 用 Session 更简单，撤销/过期由服务端控制。
 */
@Configuration
public class ApiSecurityConfiguration {

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        // CSRF token 仓库：放进【JS 可读】的 XSRF-TOKEN Cookie（不设 HttpOnly），
        // 前端读出后回传到 X-XSRF-TOKEN Header，服务端比对（double-submit 防护）。
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        return http
                // 启用 CSRF（旧项目 disable，本项目必须开——因为用 Cookie 认证）。
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(           // ← 加这个
                                "/api/v1/web/auth/register",
                                "/api/v1/web/auth/login",
                                "/api/v1/web/auth/send-code",
                                "/api/v1/web/csrf",
                                // L12：CLI 配对两端点匿名调用（CLI 无 Cookie/CSRF token），豁免 CSRF。
                                // 注意只豁免这两个 CLI 端点；/web/device-pairings/confirm 仍需 CSRF（浏览器写）。
                                "/api/v1/cli/device-pairings",
                                "/api/v1/cli/device-pairings/token"))
                // Session 策略：需要时创建（登录后保存认证用）。区别于旧项目的 STATELESS。
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .formLogin(AbstractHttpConfigurer::disable)   // 我们用自己的 JSON 登录接口，不用表单页
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)      // 登出由 WebAuthController 处理
                .authorizeHttpRequests(authorize -> authorize
                        // 白名单：探针 + 匿名可读 + 认证入口（注册/登录/获取CSRF）
                        .requestMatchers("/actuator/health", "/api/v1/system/ping").permitAll()
                        .requestMatchers("/api/v1/web/csrf").permitAll()
                        .requestMatchers("/api/v1/web/auth/register", "/api/v1/web/auth/login").permitAll()
                        .requestMatchers("/api/v1/web/auth/send-code").permitAll()
                        .requestMatchers("/api/v1/public/**").permitAll()
                        // L12：CLI 设备配对发起 + 轮询换 token 为匿名端点（CLI 尚未持有任何令牌，
                        // 靠 deviceCode 一次性 + 10min 过期 + 库存 digest 自保）。仅这两个；其余 /cli/** 留 L13 令牌链保护。
                        .requestMatchers("/api/v1/cli/device-pairings", "/api/v1/cli/device-pairings/token").permitAll()
                        // Swagger UI 接口文档查看器：放行【文档页】本身（本地调试用）。
                        // 注意：只放行文档页，业务接口仍需登录+CSRF——安全没松。生产环境应按 profile 收紧。
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 其余一律需要登录（区别于 L01 的 denyAll——那时连登录都没有）
                        .anyRequest().authenticated())
                // 未认证访问受保护资源时返回 401（语义："不知道你是谁，去登录"）。
                // Spring Security 默认返回 403，但 401 更准确，前端收到可直接跳登录页。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** 密码编码器：bcrypt（自带盐、慢哈希抗暴力破解）。注册存哈希、登录比对都用它。 */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 暴露 AuthenticationManager 供登录接口调用（内部会用 AppUserDetailsService + PasswordEncoder）。 */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }
}
