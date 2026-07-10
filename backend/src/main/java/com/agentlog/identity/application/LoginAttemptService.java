package com.agentlog.identity.application;

import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * L11.5 登录失败限流（Redis）。对照旧论坛 springBootDemo：连续失败 5 次锁 15 分钟，防暴力撞库。
 *
 * 【为什么 key 用 email 不用 userId】认证发生在"查到用户"之前，用 email 能更早拦住撞库/枚举，
 *   连"这个邮箱存不存在"都不用先泄露。
 */
@Service
public class LoginAttemptService {

    private static final String FAIL_KEY_PREFIX = "auth:login:fail:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration LOCK_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 登录前调用：已达上限则拒绝，不再走认证。 */
    public void assertNotLocked(String email) {
        String value = redis.opsForValue().get(FAIL_KEY_PREFIX + email);
        if (value != null && Integer.parseInt(value) >= MAX_ATTEMPTS) {//如果非空且＞限制则抛错
            throw new ApiException(ApiStatus.LOGIN_LOCKED);
        }
    }

    /** 认证失败后调用：计数 +1；首次失败才开始计时（避免隔天多记）。 */
    public void recordFailure(String email) {
        String key = FAIL_KEY_PREFIX + email;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, LOCK_TTL);
        }
    }

    /** 登录成功后调用：清零失败记录。 */
    public void clear(String email) {
        redis.delete(FAIL_KEY_PREFIX + email);
    }
}
