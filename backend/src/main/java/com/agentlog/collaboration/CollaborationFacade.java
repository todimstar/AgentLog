package com.agentlog.collaboration;

import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * collaboration 模块对外的公开 API（L17 引入）。
 *
 * <p>★ 为什么它在<b>模块根包</b>而不是 application 子包：
 * Spring Modulith 的规矩是「根包 = 公开 API，子包 = 私有」，由 {@code ModularityTest} 用
 * ArchUnit 静态分析字节码强制。放这里，别的模块才 import 得到；
 * 实现类留在 {@code application} 私有子包里，外面看不见。
 * ——与 L16 的 {@link com.agentlog.content.ContentFacade} 完全同构。
 *
 * <h3>★ 它是被 ModularityTest 打红之后才诞生的（诚实记录）</h3>
 * L17 施工时 {@code reliability} 模块的 Worker 直接 import 了 collaboration 的
 * Mapper 与 DO（{@code ContributionAttemptMapper} / {@code ContributionTicketDO} …），
 * 一次跑出 <b>12 条违规</b>。这正是 L02 建立 {@code ModularityTest} 的意义：
 * <b>边界不靠自觉，靠机器强制</b>——人在赶工时一定会顺手 import。
 *
 * <h3>★ 为什么"宣告一棒超时"应该由 collaboration 自己做，而不是 Worker 做</h3>
 * 那七步（attempt → FAILED_TIMEOUT、ticket → FAILED_TIMEOUT、后序 → BLOCKED、
 * 尾令牌 → FROZEN、session → INVALIDATED/PAUSED_ON_ERROR）<b>全部是 ACPP 协议的状态机推进</b>，
 * 是 collaboration 的领域知识。reliability 模块的职责只是<b>「什么时候触发」</b>（定时扫描、
 * 批量占有、毒丸隔离），不该知道「一棒死了以后状态该怎么流转」。
 *
 * <p>划分判据：<b>「何时做」归 reliability，「做什么」归 collaboration。</b>
 * 这个分工也让 L18 的 retry 有现成的落点——那同样是状态机推进，同样该由本 Facade 暴露。
 */
public interface CollaborationFacade {

    /**
     * 扫描并锁定一批已过期的 attempt，返回它们的 id。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED}：被别的 Worker 实例锁住的行直接跳过，
     * 不排队等——数据库的行天然形成任务队列，不需要 Redis 锁或消息队列。
     *
     * <p>⚠️ 锁在<b>本方法的事务结束时即释放</b>，这是有意为之：
     * 它只负责「声明这批活归我这轮扫描」，真正的处理放在各自独立的小事务里
     * （毒丸隔离），所以 {@link #expireAttempt} 内部必须再有一道闸门。
     *
     * @param now       应用时钟（不用数据库 {@code NOW(3)}，时区雷见实现类注释）
     * @param batchSize 每批上限
     */
    @Transactional
    List<Long> lockExpiredAttempts(Instant now, int batchSize);

    /**
     * 宣告一条 attempt 超时，并把失败沿因果链传播出去。
     *
     * <p>★ {@code REQUIRES_NEW}：每条一个<b>独立小事务</b>。
     * 若 50 条共用一个大事务，第 23 条炸会导致整批回滚，30 秒后又扫到同样 50 条、
     * 又在第 23 条炸 → <b>永久卡死</b>，那 49 条正常的永远处理不了（毒丸消息）。
     *
     * @return true = 本次真的由我宣告；false = 闸门返回 0（已被其他 Worker 处理）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean expireAttempt(long attemptId);
}
