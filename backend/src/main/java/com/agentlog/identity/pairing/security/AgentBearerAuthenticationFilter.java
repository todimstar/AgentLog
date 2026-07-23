package com.agentlog.identity.pairing.security;

import com.agentlog.identity.pairing.domain.AgentSessionStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.AgentActingSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.AgentActingSessionMapper;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.TokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Chain 3 的验币过滤器：消费 assume 铸的 AgentActingToken（镜像 {@link OwnerBearerAuthenticationFilter}）。
 *   读 Authorization Bearer → tokenService.digest → 查 agent_acting_session → ACTIVE+未过期
 *   → AgentPrincipal 入 SecurityContext；失败委托 entryPoint 出 AGENT_TOKEN_INVALID/EXPIRED（RE_ASSUME）。
 *
 * ⚠️ 刻意不加 @Component（同 {@link OwnerBearerAuthenticationFilter}）：避免 Boot 把 OncePerRequestFilter
 *   额外注册进主 servlet 过滤器链全局生效，造成跨链误判。只由 {@link AgentSecurityConfiguration} new 进本链。
 */
public class AgentBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final AgentActingSessionMapper sessionMapper;
    private final ProblemDetailAuthenticationEntryPoint entryPoint;

    public AgentBearerAuthenticationFilter(TokenService tokenService,
            AgentActingSessionMapper sessionMapper, ProblemDetailAuthenticationEntryPoint entryPoint) {
        this.tokenService = tokenService;
        this.sessionMapper = sessionMapper;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        AgentActingSessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<AgentActingSessionDO>()
                .eq(AgentActingSessionDO::getAccessTokenDigest, tokenService.digest(rawToken)));


        if (session == null || !AgentSessionStatus.ACTIVE.getCode().equals(session.getStatus())) {
            reject(request, response, ApiStatus.AGENT_TOKEN_INVALID);
            return;
        }
        //过期
        if (session.getExpiresAt() == null || !session.getExpiresAt().isAfter(Instant.now())) {
            reject(request, response, ApiStatus.AGENT_TOKEN_EXPIRED);
            return;
        }

        AgentPrincipal principal = new AgentPrincipal(session.getAgentAccountId(), session.getOwnerUserId(),
                session.getInstallationId(), session.getSourceTool(), session.getClientRunId());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ApiStatus status)
            throws IOException {
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response,
                new AgentTokenAuthenticationException(status, List.of("RE_ASSUME")));
    }
}
