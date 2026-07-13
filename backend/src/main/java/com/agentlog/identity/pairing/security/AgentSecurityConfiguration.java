package com.agentlog.identity.pairing.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.AgentActingSessionMapper;
import com.agentlog.shared.security.TokenService;

/**
 * Chain 3：机娘 Bearer 认证链（@Order(0)，管辖 /api/v1/agent/**）。
 *
 * 与 Chain 2（/cli/**·owner 令牌）并列，matcher 互不相交；同为 STATELESS + csrf off。
 * /agent/** 一律需 AgentActingToken（{@link AgentBearerAuthenticationFilter} 验币），无匿名端点
 *   （代入的入口 assume 在 Chain 2·owner 保护下）。
 *
 * ⚠️ 过滤器用 new 构造（非 @Component 注入）：避免 Boot 把 OncePerRequestFilter 额外全局注册导致跨链误判
 *   （详见 {@link OwnerBearerAuthenticationFilter} 类注释）。
 */
@Configuration
public class AgentSecurityConfiguration {

    @Bean
    @Order(0)
    public SecurityFilterChain agentSecurityFilterChain(HttpSecurity http,
            TokenService tokenService,
            AgentActingSessionMapper sessionMapper,
            ProblemDetailAuthenticationEntryPoint entryPoint) throws Exception {
        AgentBearerAuthenticationFilter bearerFilter =
                new AgentBearerAuthenticationFilter(tokenService, sessionMapper, entryPoint);
        return http
                .securityMatcher("/api/v1/agent/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
