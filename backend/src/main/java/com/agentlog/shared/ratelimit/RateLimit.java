package com.agentlog.shared.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（Redis 令牌桶）。贴在哪个 Controller 方法上，哪个方法就有限流保护。
 *
 * <p>★ 为什么和 {@code @Idempotent} 是同一套机制（你已经懂了）：
 * 两个注解都用 {@code @Retention(RUNTIME)} + {@code @Aspect} 的 {@code @Around} 实现，
 * 都把横切逻辑从 Controller 里隐藏掉，让调用方只看到一个注解标签。
 *
 * <p>五个场景的默认参数（Pack 10-reliability/MySQL与Redis边界.md）：
 * <pre>
 *   登录        IP + username  capacity=5    refillPerSecond=0.083
 *   配对        IP             capacity=10   refillPerSecond=0.167
 *   claim handoff  agentId    capacity=30   refillPerSecond=0.5
 *   查票状态    agentId        capacity=120  refillPerSecond=2.0
 *   上传槽位    userId         capacity=30   refillPerSecond=0.5
 * </pre>
 *
 * <p>注意：本课只挂 ACPP 两个端点（claim handoff / 查票状态），
 * 登录和配对那三个留到有需要时再挂（避免动 L05/L11.5 的测试范围）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流场景标识，用于构造 Redis key 前缀和读取配置。 */
    Scope scope();

    enum Scope {
        /** {@code agentlog:rl:claim-handoff:{agentId}} */
        CLAIM_HANDOFF,
        /** {@code agentlog:rl:ticket-status:{agentId}} */
        TICKET_STATUS
    }
}
