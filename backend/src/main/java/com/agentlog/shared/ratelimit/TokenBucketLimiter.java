package com.agentlog.shared.ratelimit;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis 令牌桶限流器。
 *
 * <p>★ Redis 故障策略（Pack 10-reliability/MySQL与Redis边界.md）：
 * <ul>
 *   <li>登录、配对 → fail closed（拒绝，安全优先）</li>
 *   <li>普通读 / ACPP 写 → <b>fail open</b>（放行，限流只是容量保护，多放几次没后果）</li>
 * </ul>
 * 本课挂的两个场景（claim handoff / 查票状态）都走 fail open。
 *
 * <p>★ 判据（和 MySQL 的锁/状态机分工的判据相同）：
 * 「这个计数错了会不会破坏不变量？」
 * 不变量（谁抢到令牌、谁抢到租约）永远由 MySQL 守；Redis 限流错了只是多放几次请求，
 * 不会改变任何数据，所以 Redis 挂了可以放行。
 * 相比之下，登录锁定是安全不变量（多放一次 = 给攻击者一次猜密码的机会），
 * 所以它必须放在 MySQL。
 */
@Component
public class TokenBucketLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketLimiter.class);

    private static final RedisScript<List<Long>> SCRIPT;

    static {
        DefaultRedisScript<List<Long>> s = new DefaultRedisScript<>();
        s.setLocation(new org.springframework.core.io.ClassPathResource("redis-token-bucket.lua"));
        // noinspection unchecked
        s.setResultType((Class<List<Long>>) (Class<?>) List.class);
        SCRIPT = s;
    }

    private final StringRedisTemplate redis;

    public TokenBucketLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试从指定桶里取 1 个令牌。
     *
     * @param key              Redis key（由切面按场景拼，如 "agentlog:rl:ticket-status:42"）
     * @param capacity         桶容量（突发上限）
     * @param refillPerSecond  每秒补充速率
     * @return true = 放行；false = 超限
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSecond) {
        try {
            long nowMs = Instant.now().toEpochMilli();
            List<Long> result = redis.execute(
                    SCRIPT,
                    List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSecond),
                    String.valueOf(nowMs),
                    "1");
            return result != null && !result.isEmpty() && result.get(0) == 1L;
        } catch (Exception e) {
            // ★ fail open：Redis 挂了照样放行（限流只是容量保护）
            log.warn("Redis 限流检查失败，fail open: key={}", key, e);
            return true;
        }
    }
}
