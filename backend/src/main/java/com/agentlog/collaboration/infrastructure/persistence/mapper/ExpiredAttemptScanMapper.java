package com.agentlog.collaboration.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.List;

/**
 * Worker 专用的 attempt 扫描与状态推进 Mapper。
 *
 * <p>⚠️ 这里故意【不】继承 BaseMapper，因为 Worker 对 attempt 的操作语义完全不同：
 * 所有方法都是「批量扫 + 条件改」，没有按 ID 查单行的需求；
 * 暴露 BaseMapper 的全部 CRUD 反而会让调用方误以为可以 updateById 改状态
 * （那是三步走，和 ClaimLeaseService 里刻意不给 @Version 是同一个原则）。
 */
@Mapper
public interface ExpiredAttemptScanMapper {

    /**
     * ★★★ L17 的核心扫描：锁住一批过期的 attempt，返回它们的 id。
     *
     * <p>★ 为什么用 {@code FOR UPDATE SKIP LOCKED}，而不是 L15/L16 的「一条 UPDATE + affectedRows」：
     * <ul>
     *   <li>条件 UPDATE 把"检查"和"动作"合进一条语句，前提是整件事只有<b>一步</b>。
     *   <li>Worker 处理一条过期 attempt 要做七八件事（改 ticket / 后序 / 令牌 / session /
     *       写 error_report / 发事件……），根本无法塞进一条 SQL。
     *   <li>所以必须先「声明所有权」（FOR UPDATE 锁住），再逐步执行。
     *   <li>SKIP LOCKED：同时运行的另一个 Worker 实例遇到被锁的行直接跳过，
     *       去拿下一批——数据库的行天然形成任务队列，不需要 Redis 锁或消息队列。
     * </ul>
     *
     * <p>★ 为什么用应用时钟 {@code #{now}} 而不是 {@code NOW(3)}（时区雷，L15 血的教训）：
     * {@code lease_expires_at} 是 Java 写进去的 DATETIME，NOW(3) 是数据库自己的时钟，
     * 两者的时区口径由 JDBC 连接参数决定——测试容器不带 serverTimezone=UTC 时差 8 小时。
     * 用 #{now} 后写入与比较走同一条驱动转换路径，偏移自动抵消。
     * ★ 这里的判断方向（&lt;）比 L16 submit 的 (&gt;=) 更危险：
     *   L16 判错后测试立刻红（该拒的放过）；L17 判错只会静默少捞/多捞，
     *   测试不报警——所以需要专门的时区探针测试守。
     *
     * @param now       应用时钟（不用数据库 NOW(3)，原因见上）
     * @param batchSize 每批上限（对应 worker.expired-attempt.batch-size 配置）
     * @return 被锁住的 attempt id 列表（同一事务内有效）
     */
    List<Long> lockExpiredAttempts(@Param("now") Instant now,
                                   @Param("batchSize") int batchSize);

    /**
     * ★ expireOne 的闸门（第一步）：把「状态还是 ACTIVE 且确实已过期」合成一条 UPDATE。
     *
     * <p>为什么 expireOne 还要再校验一次，明明 lockExpiredAttempts 已经找到这个 id：
     * lockExpiredAttempts 的 FOR UPDATE 在那个事务结束时就放锁了；到 expireOne 开始时，
     * 另一个 Worker 实例可能已经处理了同一条 attempt（status 已经是 FAILED_TIMEOUT）。
     * ——这里和 L15/L16 闸门的原理完全一样：先原子改、改不动才知道"我来晚了"。
     *
     * <p>注意：这里【不】防机娘 submit，因为扫描条件 lease_expires_at &lt; now 和
     * submit 的条件 lease_expires_at &gt;= now 互斥——能被 Worker 捞到的 attempt，
     * 机娘已经提交不进来了。闸门只防 Worker 之间互相踩。
     *
     * @return 影响行数（0 = 已被其他 Worker 处理，1 = 本次获得独占权）
     */
    int markAttemptTimeout(@Param("attemptId") Long attemptId,
                           @Param("now") Instant now);

    /** attempt 超时后把 ticket 改成 FAILED_TIMEOUT。 */
    int markTicketTimeout(@Param("ticketId") Long ticketId,
                          @Param("now") Instant now);

    /**
     * 唤醒是 wakeSuccessor 的镜像，这里是「冻结后序」：
     * 把 predecessor_ticket_id 指向超时票、且状态是 WAITING_PREDECESSOR 的后序票
     * 全部改成 BLOCKED_BY_PREDECESSOR。
     *
     * <p>status 写进 WHERE 的原因：只阻塞等待中的后序，DONE / LEASED 状态的票不动。
     *
     * @return 被阻塞的后序票数量
     */
    int blockSuccessors(@Param("predecessorTicketId") Long predecessorTicketId,
                        @Param("now") Instant now);

    /**
     * 冻结尾令牌（仅 1 根）。
     *
     * <p>尾令牌 FROZEN 的语义：「不许再有新人入队」——因为链条已经断在某棒了，
     * 新机娘拿着令牌进来也只会永远等不到前序完成。早拒绝比让它排进来再等好。
     *
     * <p>★ 与后序票阻塞的区别：
     * 后序票（N 张）管「已经进来的人能不能写」；
     * 尾令牌（1 根）管「还能不能有新人进来」。
     * 两个正交维度，一次超时同时动它们。
     */
    int freezeTailToken(@Param("sessionId") Long sessionId,
                        @Param("now") Instant now);
}
