package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.HandoffTokenDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * handoff_token 的持久化。**本课最值钱的一招在这里。**
 *
 * 简单 insert/select 继承 BaseMapper；接力棒的消费走 XML 里的原子 UPDATE
 * （Pack 持久化选型：「Handoff 抢占 → XML 原子 UPDATE」）。
 */
@Mapper
public interface HandoffTokenMapper extends BaseMapper<HandoffTokenDO> {

    /**
     * 【原子消费】接力棒：把一张 AVAILABLE 且未过期的令牌抢成 CONSUMED。
     *
     * <p>★ 返回值就是裁决书：
     * <pre>
     *   1 → 你抢到了（这一行本来是 AVAILABLE 且没过期，现在归你了）
     *   0 → 没抢到（已被消费 / 被冻结 / 被吊销 / 已过期 / 压根不存在——需回查判定具体原因）
     * </pre>
     *
     * <p>★ 为什么必须是【一条】UPDATE，而不是「查 → 判 → 改」三步：
     * <pre>
     *   三步走（错）：
     *     T1 线程A SELECT → AVAILABLE
     *     T2 线程B SELECT → AVAILABLE      ← 命门：B 也读到了 AVAILABLE
     *     T3 线程A 判定通过
     *     T4 线程B 判定通过
     *     T5 线程A UPDATE → CONSUMED
     *     T6 线程B UPDATE → CONSUMED       ← 双双"成功"，同一张令牌产出两个席位
     *   这是经典的 TOCTOU（Time-Of-Check to Time-Of-Use）竞态：
     *   SELECT 一结束就放锁了，if 判断与 UPDATE 之间那个空窗就是竞态的窝。
     *
     *   一条 UPDATE（对）：
     *     MySQL 扫到 token_digest 命中的行 → 立刻加【排他锁 X】
     *       → 求值 WHERE 的 status='AVAILABLE' AND expires_at>=NOW(3)
     *       → 成立就改，不成立就跳过 → 语句结束才放锁
     *     从"检查"到"修改"全程持锁，另一个线程连读这行的最新版本都得排队。
     * </pre>
     * 没有魔法——我们只是把判断从 Java 搬进 SQL，让"语句"这个天然的原子单位替我们做互斥。
     *
     * <p>★ 为什么不用 {@code SELECT ... FOR UPDATE}（悲观锁）：它也对，但要开事务、持锁、
     * 再发第二条 UPDATE——两条语句 + 一个事务，换来和一条语句同样的效果。能一条就一条。
     *
     * <p>★ 为什么不用 Redis 分布式锁：ADR-004 与 Pack「MySQL 与 Redis 边界」都写死了
     * 「不要用 Redis 锁替代数据库状态机」。锁是**外部约束**，进程崩了锁过期就破防；
     * 而 {@code status='AVAILABLE'} 是**数据自身的约束**，永远成立。
     *
     * <p>★ 过期为什么放在 WHERE 里而不是靠 Worker：L15 的 forbidden 明确「不写 Worker」。
     * 把 {@code expires_at >= #{now}} 写进 WHERE 做惰性判定后，
     * **正确性完全不依赖清理 Worker 是否及时**——即使过期令牌还挂着 AVAILABLE 状态，也一定抢不到。
     *
     * <p>⚠️ 时效比较用【应用传入的 now】而不是数据库的 {@code NOW(3)}：
     * 第一版用的是 NOW(3)，被 {@code joinRejectsExpiredToken} 当场打红——
     * {@code expires_at} 是 Java 写进去的 DATETIME，NOW(3) 是数据库自己的墙上时间，
     * 两者时区口径由 JDBC 连接参数决定（主库 URL 有 {@code serverTimezone=UTC}，
     * 测试的 Testcontainers URL 没有），同一份代码两处行为不一致。
     * 改用 {@code #{now}} 后写入与比较走同一条驱动转换路径，偏移自动抵消。
     * 原子性不受影响——那来自"单条 UPDATE 持行锁"，与用谁的时钟无关。
     *
     * @param tokenDigest 客户端明文令牌的 HMAC 摘要（明文从不落库）
     * @param agentId     消费者机娘 id（来自 agent 令牌，非客户端传入）——同时定下新席位的 required_agent_id
     * @param now         消费时刻（由 Clock 注入，便于测试控时；过期判定另用库时钟 NOW(3)）
     * @return 影响行数：1 = 抢到，0 = 没抢到
     */
    int consumeAvailableToken(@Param("tokenDigest") byte[] tokenDigest,
                              @Param("agentId") Long agentId,
                              @Param("now") Instant now);

    /**
     * 回填「这张令牌换出了哪张席位」（consumed_ticket_id）。
     *
     * <p>★ 为什么要拆成第二条语句、而不是在上面那条原子 UPDATE 里一起写：
     * <pre>
     *   如果一起写，就必须【先建票拿到 ticketId 才能消费】，于是执行顺序变成：
     *     查令牌 → 建票 → 原子消费
     *   两个线程抢同一张令牌时，它们会【双双先去建票】（此时还没人被拦），
     *   sequence_no 都算成同一个值 → 第二个撞 uk_ticket_sequence 抛 DuplicateKeyException。
     *   结果：败者拿到的是丑陋的约束冲突（500），而不是干净的 409 + 「向主人索取新尾令牌」。
     *   ——这正是「靠唯一键兜底 = 用异常控制业务流程」的反面教材。
     *
     *   正确的顺序是【闸门优先】：
     *     查令牌(取上下文) → ★原子消费(闸门)★ → 建票 → 回填 → 签发新尾令牌
     *   过了闸门就已经【独占】这张令牌，后面每一步都没有任何竞争者，
     *   连 uk_ticket_sequence 都不会再被触发（它退回它该有的角色：最后的安全网）。
     * </pre>
     * 所以本方法是在"已经赢了"之后按主键更新，天然无竞争。
     */
    int linkConsumedTicket(@Param("id") Long id, @Param("ticketId") Long ticketId);

    /**
     * 按摘要点查（消费失败后回查，用于把"0 行"翻译成具体的错误码与自愈动作）。
     *
     * 注意这个查询**只用于诊断**，不参与互斥判定——判定已经由上面那条 UPDATE 一次性完成。
     * 换句话说：先原子改，改不动才回头问"为什么"。顺序反了就又变成三步走了。
     */
    HandoffTokenDO selectByDigest(@Param("tokenDigest") byte[] tokenDigest);
}
