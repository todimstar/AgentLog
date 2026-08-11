package com.agentlog.audit.application;

import com.agentlog.audit.infrastructure.persistence.dataobject.AuditRecordDO;
import com.agentlog.audit.infrastructure.persistence.mapper.AuditRecordMapper;
import com.agentlog.shared.event.AttemptExpired;
import com.agentlog.shared.event.ContributionSubmitted;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * 事件监听器：把领域事件翻译成 audit_record 流水。
 *
 * <p>★ {@code @ApplicationModuleListener} = {@code @Async} + {@code @Transactional(REQUIRES_NEW)}
 * + Spring Modulith 发件箱保证。这意味着：
 * <ul>
 *   <li>监听器在<b>业务事务提交之后</b>的派生事务里执行；</li>
 *   <li>监听器抛异常 → 业务<b>不回滚</b>，publication 留在 EVENT_PUBLICATION 表等重投；</li>
 *   <li>重启后 Modulith 自动重投未完成的 publication。</li>
 * </ul>
 *
 * <p>注意：状态推进（attempt FAILED_TIMEOUT / ticket BLOCKED 等）已经在 Worker 的业务事务里
 * <b>同步完成</b>，这里只做「派生行为」——写审计流水。
 * 不变量由数据库状态机守，事件只做派生，不能反过来。
 */
@Service
public class AuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuditListener.class);

    private final AuditRecordMapper mapper;
    private final Clock clock;

    public AuditListener(AuditRecordMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void onAttemptExpired(AttemptExpired e) {
        Instant now = Instant.now(clock);
        // 超时宣告：一条主记录
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "ATTEMPT_EXPIRED",
                String.format("第 %d 棒租约超时（attempt #%d）", e.sequenceNo(), e.attemptId()),
                String.format("{\"attemptId\":%d,\"errorReportId\":%d,\"firstTurn\":%b}",
                        e.attemptId(), e.errorReportId(), e.firstTurn()),
                now);

        // 后序票被阻塞（一条 session 级的记录，而不是每张后续票一条）
        String blockedAction = e.firstTurn() ? "SESSION_INVALIDATED" : "SESSION_PAUSED";
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), null,
                blockedAction,
                e.firstTurn()
                        ? "首棒超时，会话作废（无草稿暴露）"
                        : "中间棒超时，会话暂停，后序等待 retry 或 terminate",
                null, now);

        log.debug("audit: attempt {} 超时事件已记录", e.attemptId());
    }

    @ApplicationModuleListener
    public void onContributionSubmitted(ContributionSubmitted e) {
        Instant now = Instant.now(clock);
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "CONTRIBUTION_SUBMITTED",
                String.format("第 %d 棒提交成功%s",
                        e.sequenceNo(), e.firstTurn() ? "（首棒，草稿已创建）" : ""),
                String.format("{\"contributionId\":%d,\"firstTurn\":%b}", e.contributionId(), e.firstTurn()),
                now);
    }

    private void insertRecord(Long ownerUserId, Long sessionId, Long ticketId, Long agentId,
                               String actionType, String summary, String detailJson, Instant now) {
        AuditRecordDO r = new AuditRecordDO();
        r.setOwnerUserId(ownerUserId);
        r.setSessionId(sessionId);
        r.setTicketId(ticketId);
        r.setAgentId(agentId);
        r.setActionType(actionType);
        r.setSummary(summary);
        r.setDetailJson(detailJson);
        r.setCreatedAt(now);
        mapper.insert(r);
    }
}
