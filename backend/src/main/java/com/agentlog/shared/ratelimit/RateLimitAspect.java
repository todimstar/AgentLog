package com.agentlog.shared.ratelimit;

import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.AgentIdentity;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 限流切面（与 L16 的 {@code IdempotencyAspect} 是同一套机制，你已经懂了）。
 *
 * <p>调用点：{@code @RateLimit} 注解贴在 Controller 方法上，Spring 启动时
 * 给那个 Bean 套一层动态代理，请求进来时先跑切面，后跑原方法。
 *
 * <p>Key 拼接规则：
 * <pre>
 *   CLAIM_HANDOFF  → "agentlog:rl:claim-handoff:{agentId}"
 *   TICKET_STATUS  → "agentlog:rl:ticket-status:{agentId}"
 * </pre>
 * 按【机娘 id】隔离——10 个机娘并行，各有各的额度互不影响。
 */
@Aspect
@Component
public class RateLimitAspect {

    private final TokenBucketLimiter limiter;
    private final RateLimitProperties props;

    public RateLimitAspect(TokenBucketLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = buildKey(rateLimit.scope());
        RateLimitProperties.Bucket bucket = props.getBucket(rateLimit.scope());

        if (!limiter.tryAcquire(key, bucket.capacity(), bucket.refillPerSecond())) {
            throw new ApiException(ApiStatus.RATE_LIMIT_EXCEEDED);
        }
        return pjp.proceed();
    }

    private String buildKey(RateLimit.Scope scope) {
        Long agentId = currentAgentId();
        return switch (scope) {
            case CLAIM_HANDOFF  -> "agentlog:rl:claim-handoff:"  + agentId;
            case TICKET_STATUS  -> "agentlog:rl:ticket-status:"  + agentId;
        };
    }

    private Long currentAgentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AgentIdentity ai) {
            return ai.agentAccountId();
        }
        return -1L;   // 无认证时不应到达这里（Chain 3 已拦截），-1 兜底隔离
    }
}
