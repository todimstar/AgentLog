package com.agentlog.shared.idempotency;

import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.AgentIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@link Idempotent} 的执行者：把「同一请求发两次，效果与发一次一致」实现成一层横切（L16 · ADR-0006）。
 *
 * <h3>完整流程</h3>
 * <pre>
 *   ① 取 Idempotency-Key 头；没有 → 宽松放行（或 required=true 时 400）
 *   ② 算 requestHash = SHA-256(方法参数 JSON)   ← 路径参数与请求体都在参数里，天然一起进 hash
 *   ③ 抢占式 INSERT 台账(IN_PROGRESS)  【独立事务，立即提交】
 *        └ 撞 uk_idempotency → 回查台账：
 *             hash 不同        → 409 KEY_REUSED_WITH_DIFFERENT_BODY（一个 key 用在了两件事上）
 *             hash 同 + 完成   → 反序列化旧响应直接返回，【业务一行都不执行】
 *             hash 同 + 处理中 → 409 REQUEST_IN_PROGRESS（稍后原样重试是安全的）
 *   ④ 执行业务（它自己的 @Transactional）
 *   ⑤ 成功 → 台账落 COMPLETED + 缓存响应体 【独立事务】
 *     失败 → 删掉台账，让客户端能用同一个 key 重试 【独立事务】
 * </pre>
 *
 * <h3>★ 为什么 catch 必须写在这里，而不是 IdempotencyStore 里面</h3>
 * {@code begin()} 撞唯一键抛异常时，<b>它所在的那个事务已被标记 rollback-only</b> ——
 * 在方法内部 catch 之后再碰数据库一律失败。捕获必须发生在事务边界之外（即这里），
 * 让那个小事务先干净地回滚掉，我们再用一个新事务去回查。
 *
 * <h3>★ 为什么先判 hash、后判状态</h3>
 * Pack {@code 10-reliability/idempotency.md} 的行为表规定：同 key 不同 hash 一律 409 KEY_REUSED，
 * <b>与那条记录当前是什么状态无关</b>。因为「同一个 key 被用在两件不同的事上」本身就是客户端的 bug，
 * 无论前一件事做完没做完，都不该把 A 的响应返回给 B。
 */
@Aspect
@Component
public class IdempotencyAspect {

    /** 活契约 components.securitySchemes.idempotencyKey 定义的头名。 */
    public static final String HEADER = "Idempotency-Key";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {//AOP捕获controller方法，在方法上方切入套本方法
        HttpServletRequest request = currentRequest();
        String key = (request == null) ? null : request.getHeader(HEADER);

        if (!StringUtils.hasText(key)) {//看请求头里的Idempotency-Key是否存在
            if (idempotent.required()) {
                throw new ApiException(ApiStatus.IDEMPOTENCY_KEY_REQUIRED);
            }
            // 宽松模式：没带 key 就是普通请求。保证 L15 那批既有客户端不会因本课上线而全挂。
            return pjp.proceed();
        }

        AgentIdentity principal = findPrincipal(pjp.getArgs());//看看有没有Chain3的认证体
        if (principal == null) {
            // 幂等域的隔离键之一是 agent_id（NOT NULL），非机娘端点本课不支持幂等，透明放行。
            return pjp.proceed();
        }

        String endpoint = routeTemplate(request);
        String requestHash = hashOf(pjp.getArgs());//哈希请求体和controller方法参数里的路径参数

        IdempotencyRecordDO acquired;
        try {//先存幂等请求
            acquired = store.begin(principal.ownerUserId(), principal.agentAccountId(),
                    endpoint, key, requestHash);
        } catch (DuplicateKeyException conflict) {//利用唯一键冲突判断是否已有记录
            // 有人先来了（可能是并发，也可能是同一个客户端重发）。回查台账决定怎么应答。
            return replayOrReject(
                    store.find(principal.ownerUserId(), principal.agentAccountId(), endpoint, key),
                    requestHash, pjp);
        }
        //能到这就是非幂等的首次请求，执行完存一下返回体给下次用
        try {
            Object result = pjp.proceed();//放行并拿到业务的返回值，套在controller上切入的
            store.complete(acquired.getId(), responseStatusOf(pjp), objectMapper.writeValueAsString(result));
            return result;
        } catch (Throwable businessFailure) {
            // 什么都没生效 → 台账不该留下，否则客户端拿着同一个 key 永远撞墙。
            store.abandon(acquired.getId());
            throw businessFailure;
        }
    }

    /** 抢占失败后的应答决策。顺序固定：先比 hash，再看状态（理由见类注释）。 */
    private Object replayOrReject(IdempotencyRecordDO existing, String requestHash, ProceedingJoinPoint pjp)
            throws Exception {
        if (existing == null) {
            // 极罕见：撞了唯一键、回查却没有（并发的失败分支刚把它删掉）。
            // 当作「处理中」让客户端稍后重试——比猜测更安全。
            throw new ApiException(ApiStatus.IDEMPOTENCY_REQUEST_IN_PROGRESS);
        }
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new ApiException(ApiStatus.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY);
        }
        if (IdempotencyRecordDO.Status.COMPLETED.getCode().equals(existing.getStatus())
                && StringUtils.hasText(existing.getResponseBodyJson())) {
            // ★ 重放：业务一行都不执行，直接把上次的响应原样还回去。
            //   返回类型从方法签名取，所以反序列化出来的对象与首次返回的完全同型，
            //   @ResponseStatus 也照旧生效 —— 客户端拿到的东西字节级一致。
            Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
            return objectMapper.readValue(existing.getResponseBodyJson(), returnType);
        }
        throw new ApiException(ApiStatus.IDEMPOTENCY_REQUEST_IN_PROGRESS);
    }

    /**
     * requestHash = SHA-256(方法参数的 JSON)。
     *
     * <p>★ 用<b>方法参数</b>而不是原始请求体，一举两得：路径参数（如 ticketCode）与请求体都已经绑定成对象，
     * 天然一起进 hash。于是「同一个 key 用在两张不同的票上」会被识别成 KEY_REUSED，
     * 而不是被当成重放返回错误的响应 —— 这正是台账的 endpoint 列要存<b>路由模板</b>而非实际 URI 的配套。
     *
     * <p>principal 不进 hash：幂等域已经按 (owner, agent) 隔离，再算一遍是冗余；
     * 且它是接口实现，序列化形态不稳定。
     */
    private String hashOf(Object[] args) {
        List<Object> hashable = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof AgentIdentity) {
                continue;
            }
            hashable.add(arg);
        }
        try {
            String json = objectMapper.writeValueAsString(hashable);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化请求参数用于幂等哈希", e);
        }
    }

    /** 例：{@code POST /api/v1/agent/contribution-tickets/{ticketCode}/leases}。 */
    private String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return request.getMethod() + " " + (pattern == null ? request.getRequestURI() : pattern);
    }

    private int responseStatusOf(ProceedingJoinPoint pjp) {
        ResponseStatus annotation = AnnotatedElementUtils.findMergedAnnotation(
                ((MethodSignature) pjp.getSignature()).getMethod(), ResponseStatus.class);
        return annotation == null ? 200 : annotation.code().value();
    }

    private AgentIdentity findPrincipal(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof AgentIdentity identity) {
                return identity;
            }
        }
        return null;
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
