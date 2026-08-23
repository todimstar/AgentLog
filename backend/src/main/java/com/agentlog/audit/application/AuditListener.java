package com.agentlog.audit.application;

import com.agentlog.audit.infrastructure.persistence.dataobject.AuditRecordDO;
import com.agentlog.audit.infrastructure.persistence.mapper.AuditRecordMapper;
import com.agentlog.shared.event.AttemptExpired;
import com.agentlog.shared.event.AttemptFailedByClient;
import com.agentlog.shared.event.CollaborationStarted;
import com.agentlog.shared.event.CollaborationStopped;
import com.agentlog.shared.event.ContributionSubmitted;
import com.agentlog.shared.event.HandoffClaimed;
import com.agentlog.shared.event.HandoffReissued;
import com.agentlog.shared.event.LeaseClaimed;
import com.agentlog.shared.event.TicketRetried;
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

    // ════════════════════════════════════════════════════════════════
    //  ↓↓↓ L18 补齐：时间线的前半段与「人的决策」段 ↓↓↓
    //
    //  ★ 前三个（COLLAB_STARTED / HANDOFF_CLAIMED / LEASE_CLAIMED）不是新功能，是【还债】：
    //    它们从 V014 起就写在 ck_audit_action_type 的值集里，却【从来没有代码产生过】。
    //    后果直到 L18 做时间线页才暴露 —— 页面上一条协作会【凭空从「第 1 棒提交成功」开始】，
    //    谁开的局、谁接的棒、谁领的租约全是空白。
    //
    //  ★ 教训：CHECK 值集里有个值，不等于有代码会产生它。【值集是承诺，不是实现】，
    //    而没有任何机器能检查「承诺有没有兑现」——测试只测「代码做了什么」，
    //    测不出「代码答应了却没做什么」。
    // ════════════════════════════════════════════════════════════════

    @ApplicationModuleListener
    public void onCollaborationStarted(CollaborationStarted e) {
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "COLLAB_STARTED",
                String.format("协作开局：《%s》，首棒已就位", e.plannedTitle()),
                String.format("{\"postTicket\":\"%s\"}", e.postTicket()),
                Instant.now(clock));
    }

    @ApplicationModuleListener
    public void onHandoffClaimed(HandoffClaimed e) {
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "HANDOFF_CLAIMED",
                String.format("机娘用接力棒加入，排到第 %d 棒", e.sequenceNo()),
                String.format("{\"predecessorTicketId\":%d}", e.predecessorTicketId()),
                Instant.now(clock));
    }

    @ApplicationModuleListener
    public void onLeaseClaimed(LeaseClaimed e) {
        // ★ attemptNo > 1 说明这一棒之前失败过、是主人 retry 之后重开的 —— 时间线要说清楚。
        boolean isRetryRun = e.attemptNo() != null && e.attemptNo() > 1;
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "LEASE_CLAIMED",
                String.format("第 %d 棒开始写作%s", e.sequenceNo(),
                        isRetryRun ? String.format("（第 %d 次尝试）", e.attemptNo()) : ""),
                String.format("{\"attemptId\":%d,\"attemptNo\":%d,\"leaseExpiresAt\":\"%s\"}",
                        e.attemptId(), e.attemptNo(), e.leaseExpiresAt()),
                Instant.now(clock));
    }

    @ApplicationModuleListener
    public void onTicketRetried(TicketRetried e) {
        Instant now = Instant.now(clock);
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.requiredAgentId(),
                "TICKET_RETRIED",
                String.format("主人重试第 %d 棒，等待原机娘用新对话继续（将是第 %d 次尝试）",
                        e.sequenceNo(), e.nextAttemptNo()),
                String.format("{\"unblockedSuccessors\":%d,\"handoffUnfrozen\":%b}",
                        e.unblockedCount(), e.handoffUnfrozen()),
                now);
    }

    @ApplicationModuleListener
    public void onCollaborationStopped(CollaborationStopped e) {
        Instant now = Instant.now(clock);
        insertRecord(e.ownerUserId(), e.sessionId(), null, null,
                "SESSION_STOPPED",
                "主人结束了协作，草稿已交还审稿",
                String.format("{\"revokedAttemptId\":%s,\"handoffRevoked\":%b}",
                        e.revokedAttemptId(), e.handoffRevoked()),
                now);
        // ★ 席位取消单独记一条【session 级汇总】，而不是每张票一条 ——
        //   时间线是给人看的，N 张票刷 N 条噪音只会淹没真正重要的那几条。
        if (e.cancelledTicketCount() > 0) {
            insertRecord(e.ownerUserId(), e.sessionId(), null, null,
                    "TICKET_CANCELLED",
                    String.format("取消了 %d 个未完成席位", e.cancelledTicketCount()),
                    String.format("{\"count\":%d}", e.cancelledTicketCount()),
                    now);
        }
    }

    @ApplicationModuleListener
    public void onHandoffReissued(HandoffReissued e) {
        insertRecord(e.ownerUserId(), e.sessionId(), e.predecessorTicketId(), null,
                "HANDOFF_REISSUED",
                "主人重新签发了尾令牌，旧的已失效",
                String.format("{\"oldTokenId\":%d,\"newTokenId\":%d}", e.oldTokenId(), e.newTokenId()),
                Instant.now(clock));
    }

    @ApplicationModuleListener
    public void onAttemptFailedByClient(AttemptFailedByClient e) {
        Instant now = Instant.now(clock);
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), e.agentId(),
                "ATTEMPT_FAILED_CLIENT",
                String.format("第 %d 棒的机娘主动报告失败", e.sequenceNo()),
                String.format("{\"attemptId\":%d,\"firstTurn\":%b}", e.attemptId(), e.firstTurn()),
                now);
        // 与 Worker 超时那条路径同构：会话级的后果也记一条。
        insertRecord(e.ownerUserId(), e.sessionId(), e.ticketId(), null,
                e.firstTurn() ? "SESSION_INVALIDATED" : "SESSION_PAUSED",
                e.firstTurn()
                        ? "首棒自报失败，会话作废（无草稿暴露）"
                        : "中间棒自报失败，会话暂停，等待 retry 或结束协作",
                null, now);
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
//8.21：整一个异步mapper存进事件表里记录的记录员，监听器