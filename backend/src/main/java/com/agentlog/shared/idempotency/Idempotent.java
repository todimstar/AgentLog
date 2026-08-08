package com.agentlog.shared.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 Controller 方法上：本端点<b>要求并支持</b> Idempotency-Key（L16 · ADR-0006）。
 *
 * <h3>幂等是什么、为什么需要</h3>
 * 幂等 = <b>同一个请求发两次，效果与发一次完全一致</b>。
 * 现实里的必要性：CLI 提交时网络超时，客户端<b>无法判断服务端收没收到</b> ——
 * 不重发可能真的丢了，重发又可能写出两段正文。幂等让「重发」变成安全动作。
 *
 * <h3>为什么用 AOP，而不是 L13 那两层 Filter</h3>
 * Filter / Interceptor 拿到的是<b>字节流</b>，要缓存响应体必须包一层
 * {@code ContentCachingResponseWrapper}，还要处理编码与提交时机；
 * 而环绕切面直接拿到 Controller 方法的<b>返回值对象</b>，
 * Jackson 序列化存库、重放时反序列化返回，干净得多。
 *
 * <h3>显式注解而不是「凡带 Idempotency-Key 就生效」</h3>
 * 幂等要求端点是「可被重放且能返回同一结果」的，不是所有端点都满足。
 * 用注解显式声明，让「哪些端点有幂等语义」在代码里一眼可见，
 * 也避免 AOP 变成看不见的魔法（这是引入 AOP 的主要代价，用显式注解抵消）。
 *
 * @see IdempotencyAspect 具体流程与事务边界
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /**
     * 是否强制要求携带 Idempotency-Key。
     *
     * <p>默认 false（宽松）：没带 key 就当普通请求直接放行，不拦。
     * 这样活契约里「声明了 idempotencyKey」的既有客户端（L15 的 CLI）不会因为本课上线而突然全挂。
     * 需要严格保证的端点可以显式打开。
     */
    boolean required() default false;
}
