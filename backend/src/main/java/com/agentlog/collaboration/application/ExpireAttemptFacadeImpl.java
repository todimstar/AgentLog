package com.agentlog.collaboration.application;

import com.agentlog.collaboration.CollaborationFacade;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ExpiredAttemptScanMapper;
import com.agentlog.shared.event.AttemptExpired;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CollaborationFacade} 的实现——「一棒死了之后，状态机怎么流转」（L17 · TX-06 的状态推进部分）。
 *
 * <h3>★ 为什么这段逻辑在 collaboration 而不在 reliability</h3>
 * 它推进的全是 ACPP 协议自己的状态机（attempt / ticket / handoff / session），
 * 是 collaboration 的领域知识。reliability 只负责<b>「什么时候触发」</b>。
 * <p><b>「何时做」归 reliability，「做什么」归 collaboration。</b>
 *
 * <h3>七步执行顺序</h3>
 * <pre>
 *   ① ★闸门★  条件 UPDATE attempt → FAILED_TIMEOUT
 *   ② 取上下文（attempt → ticket → session）
 *   ③ ticket  → FAILED_TIMEOUT
 *   ④ 后序 ticket → BLOCKED_BY_PREDECESSOR（N 张）
 *   ⑤ 尾令牌  → FROZEN（永远 1 根）
 *   ⑥ session → INVALIDATED（首棒）/ PAUSED_ON_ERROR（中间棒）
 *   ⑦ 发布 AttemptExpired 事件（error_report 由 reliability 的监听方写——它才是"事故报告"的主人）
 * </pre>
 *
 * <h3>★ ①那道闸门防的是谁（主人在施工蓝图评审时问过）</h3>
 * <b>不防机娘 submit</b>——两个条件互斥：
 * <pre>
 *   submit 的 WHERE：lease_expires_at &gt;= now   （未过期才能提交）
 *   Worker 的 WHERE：lease_expires_at &lt;  now   （已过期才会被捞）
 * </pre>
 * 它防的是<b>另一个 Worker 实例</b>：{@link #lockExpiredAttempts} 的 {@code FOR UPDATE} 锁
 * 在那个事务结束时就放了，等到本方法开始时，那条 attempt 可能已被别人处理完。
 * <p>且必须放在七步<b>之前</b>——放在第四步才检查的话，前三步已经改了状态，闸门返回 0 时收不回来。
 */
@Service
public class ExpireAttemptFacadeImpl implements CollaborationFacade {

    private static final Logger log = LoggerFactory.getLogger(ExpireAttemptFacadeImpl.class);

    private final ExpiredAttemptScanMapper scanMapper;
    private final ContributionAttemptMapper attemptMapper;
    private final ContributionTicketMapper ticketMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ExpireAttemptFacadeImpl(ExpiredAttemptScanMapper scanMapper,
                                   ContributionAttemptMapper attemptMapper,
                                   ContributionTicketMapper ticketMapper,
                                   CollaborationSessionMapper sessionMapper,
                                   ApplicationEventPublisher events,
                                   Clock clock) {
        this.scanMapper = scanMapper;
        this.attemptMapper = attemptMapper;
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public List<Long> lockExpiredAttempts(Instant now, int batchSize) {
        return scanMapper.lockExpiredAttempts(now, batchSize);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)   // ★ 毒丸隔离：每条一个独立小事务
    public boolean expireAttempt(long attemptId) {
        Instant now = Instant.now(clock);

        // ① ★闸门★（防的是另一个 Worker，不是机娘——理由见类注释）
        if (scanMapper.markAttemptTimeout(attemptId, now) == 0) {//会update attempt的状态从Active变成timeout，0则是已经被处理
            log.debug("attempt {} 已被其他 Worker 处理，跳过", attemptId);
            return false;
        }
        // ——— 走到这里，这条 attempt 独占归我，下面没有竞争者 ———

        // ② 取上下文
        ContributionAttemptDO attempt = attemptMapper.selectByIdPlain(attemptId);
        if (attempt == null) {
            log.error("attempt {} 闸门通过后读不到数据，数据异常", attemptId);
            return false;
        }
        ContributionTicketDO ticket = ticketMapper.selectById(attempt.getTicketId());
        if (ticket == null) {
            log.error("attempt {} 对应的 ticket {} 不存在，跳过", attemptId, attempt.getTicketId());
            return false;
        }
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        if (session == null) {
            log.error("ticket {} 对应的 session 不存在，跳过", ticket.getId());
            return false;
        }

        // ③ ticket → FAILED_TIMEOUT
        scanMapper.markTicketTimeout(ticket.getId(), now);

        // ④ 后序票 → BLOCKED（N 张，与 submit 里 wakeSuccessor 方向相反）
        scanMapper.blockSuccessors(ticket.getId(), now);

        // ⑤ 尾令牌 → FROZEN（永远 1 根）
        //    ★ 与④是两个正交维度：④管「已经进来的人能不能写」，⑤管「还能不能有新人进来」。
        scanMapper.freezeTailToken(session.getId(), now);

        // ⑥ session 状态推进
        //    首棒失败 → INVALIDATED：post/draft 从来没被创建过（「首棒失败不暴露空草稿」），没东西可救。
        //    中间棒失败 → PAUSED_ON_ERROR：草稿还在，等主人 retry（L18）。
        boolean firstTurn = (ticket.getPredecessorTicketId() == null);
        String newStatus = firstTurn ? "INVALIDATED" : "PAUSED_ON_ERROR";
        session.setStatus(newStatus);
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);

        // ⑦ 发布事件。error_report / audit_record 由各自模块的监听器写——
        //    Modulith 的事务性发件箱保证「业务成功了，派生行为一定不会丢」。
        events.publishEvent(new AttemptExpired(
                attemptId, ticket.getId(), session.getId(),
                session.getOwnerUserId(), ticket.getRequiredAgentId(),
                ticket.getSequenceNo(), firstTurn, null, now));

        log.info("attempt {} (ticket={}, session={}) 已宣告超时，session → {}",
                attemptId, ticket.getId(), session.getId(), newStatus);
        return true;
    }
}
