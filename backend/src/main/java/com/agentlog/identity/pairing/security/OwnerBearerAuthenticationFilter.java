package com.agentlog.identity.pairing.security;

import com.agentlog.identity.pairing.domain.OwnerSessionStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.OwnerAccessSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.OwnerAccessSessionMapper;
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
 * Chain 2 的验币过滤器：消费 L12 铸的 OwnerAccessToken（opaque + HMAC 摘要）。
 *
 * 流程（ADR-0001）：
 *   1. 读 Authorization: Bearer &lt;明文&gt;；无则放行（下游 anyRequest().authenticated() 触发通用 401）。
 *   2. tokenService.digest(明文)（与铸币同算法）→ 按 access_token_digest 查 owner_access_session。
 *   3. 无命中 / 非 ACTIVE → OWNER_TOKEN_INVALID（去重新配对）；已过期 → OWNER_TOKEN_EXPIRED（去 refresh）。
 *   4. 命中且有效 → OwnerPrincipal{ownerUserId, installationId} 入 SecurityContext。
 *
 * 失败直接委托 entryPoint 出信封（不在此手拼 JSON），与全局错误格式一致。
 *
 * ⚠️ 刻意不加 @Component：OncePerRequestFilter 若是 Spring bean，Boot 会额外把它注册进【主 servlet 过滤器链】
 *   对所有 URL 全局生效——于是带 owner 令牌的 /agent/** 请求也会被它拦、或反之，导致跨链误判 401。
 *   正确做法是只由 {@link CliSecurityConfiguration} 用 new 构造并 addFilterBefore 进本链，严格限定作用域。
 */
public class OwnerBearerAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final OwnerAccessSessionMapper sessionMapper;
    private final ProblemDetailAuthenticationEntryPoint entryPoint;

    public OwnerBearerAuthenticationFilter(TokenService tokenService,
            OwnerAccessSessionMapper sessionMapper, ProblemDetailAuthenticationEntryPoint entryPoint) {
        this.tokenService = tokenService;
        this.sessionMapper = sessionMapper;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);   // 无令牌：交由下游 + entryPoint 出通用 401
            return;
        }

        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        OwnerAccessSessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<OwnerAccessSessionDO>()
                .eq(OwnerAccessSessionDO::getAccessTokenDigest, tokenService.digest(rawToken)));

        // 无命中 / 非 ACTIVE（含 REVOKED）→ 无效，去重新配对。
        if (session == null || !OwnerSessionStatus.ACTIVE.getCode().equals(session.getStatus())) {
            reject(request, response, ApiStatus.OWNER_TOKEN_INVALID, List.of("RE_PAIR"));
            return;
        }
        // ACTIVE 但已过期 → 去 refresh 换新（L13 下一步）。
        if (session.getAccessExpiresAt() == null || !session.getAccessExpiresAt().isAfter(Instant.now())) {
            reject(request, response, ApiStatus.OWNER_TOKEN_EXPIRED, List.of("REFRESH_TOKEN"));
            return;
        }

        OwnerPrincipal principal = new OwnerPrincipal(session.getOwnerUserId(), session.getInstallationId());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
            ApiStatus status, List<String> recoveryActions) throws IOException {
        SecurityContextHolder.clearContext();
        entryPoint.commence(request, response, new OwnerTokenAuthenticationException(status, recoveryActions));
    }
}
