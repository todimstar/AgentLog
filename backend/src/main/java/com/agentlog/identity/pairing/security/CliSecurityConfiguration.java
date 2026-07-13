package com.agentlog.identity.pairing.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Chain 2：CLI Bearer 认证链（@Order(1)，最具体优先）。管辖 /api/v1/cli/**。
 *
 * 与 Chain 1（Session+CSRF，shared.security.ApiSecurityConfiguration，@Order(2) 兜底）处处相反：
 *   STATELESS + csrf.disable()（Bearer 无 Cookie/会话，无 CSRF 面）。
 * 配对两端点保持匿名（"换令牌不能要令牌"的鸡蛋问题）；其余 /cli/** 需 owner 令牌，
 *   由 {@link OwnerBearerAuthenticationFilter} 验币、失败经 {@link ProblemDetailAuthenticationEntryPoint} 出 401。
 */
@Configuration
public class CliSecurityConfiguration {

    @Bean
    @Order(1)
    public SecurityFilterChain cliSecurityFilterChain(HttpSecurity http,
            OwnerBearerAuthenticationFilter bearerFilter,
            ProblemDetailAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .securityMatcher("/api/v1/cli/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // 换令牌不能要令牌：配对两端点匿名（CLI 尚无 token）。
                        .requestMatchers("/api/v1/cli/device-pairings", "/api/v1/cli/device-pairings/token")
                        .permitAll()
                        // 其余 /cli/** 一律需 owner 令牌。
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
