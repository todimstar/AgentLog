package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.response.TicketStatusView;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.AttemptFailedByClient;
import com.agentlog.shared.security.AgentIdentity;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机娘自报失败（L18）——{@code contribution_attempt.status='FAILED_CLIENT'} 的<b>唯一产生路径</b>。
 *
 * <h3>★ 它清掉了一个「值集列了、代码从没产生过」的死状态</h3>
 * {@code FAILED_CLIENT} 从 V013 起就在 CHECK 值集里（注释标着「机娘自己报告失败（L18）」），
 * 但在本课之前<b>没有任何端点能产生它</b>。
 * <p>本课做全景梳理时发现，四个状态机 28 个值里有 5 个从来没有代码产生过。
 * ★ 教训：<b>CHECK 值集里有个值，不等于有代码会产生它——值集是承诺，不是实现</b>，
 * 而没有任何机器能检查「承诺有没有兑现」。
 *
 * <h3>★ 为什么值得为它单开一个端点</h3>
 * 机娘知道自己写不下去了（需求不清 / 上一棒内容有问题 / 工具报错），
 * 与其<b>干等 15 分钟让租约超时</b>，不如立刻说出来：
 * <ul>
 *   <li>后序机娘早 15 分钟知道别等了；</li>
 *   <li>{@code error_report.summary} 记的是<b>真实原因</b>，而不是笼统的「租约超时」；</li>
 *   <li>主人早 15 分钟在时间线上看到红点。</li>
 * </ul>
 *
 * <h3>★ 它与 L17 Worker 走的是同一套状态推进</h3>
 * 票落失败态、后序整条尾巴阻塞、尾令牌冻结、会话暂停或作废——<b>一步不差</b>，
 * 共用 {@link FailurePropagation}。不同的只有两处：<b>触发者</b>和<b>闸门条件</b>。
 * <pre>
 *   Worker ：WHERE status='ACTIVE' AND lease_expires_at &lt; now   （已过期才轮到我收尸）
 *   自报   ：WHERE lease_token_digest=? AND status='ACTIVE'       （凭证对得上就认）
 * </pre>
 * ★ 故意<b>不校验是否过期</b>：机娘说「我写不下去了」这件事，租约过没过期都成立。
 * 若恰好与 Worker 撞上，行锁让二者串行，谁先谁赢，另一个拿 0 行干净退出——
 * 而<b>两条路径后续做的事完全相同，所以谁赢都不影响正确性</b>。
 */
@Service
public class ReportClientFailureService {

    private static final Logger log = LoggerFactory.getLogger(ReportClientFailureService.class);

    private final OwnerCollaborationMapper ownerMapper;
    private final ContributionAttemptMapper attemptMapper;
    private final ContributionTicketMapper ticketMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final FailurePropagation failurePropagation;
    private final TokenService tokenService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReportClientFailureService(OwnerCollaborationMapper ownerMapper,
                                      ContributionAttemptMapper attemptMapper,
                                      ContributionTicketMapper ticketMapper,
                                      CollaborationSessionMapper sessionMapper,
                                      FailurePropagation failurePropagation,
                                      TokenService tokenService,
                                      ApplicationEventPublisher events,
                                      Clock clock) {
        this.ownerMapper = ownerMapper;
        this.attemptMapper = attemptMapper;
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.failurePropagation = failurePropagation;
        this.tokenService = tokenService;
        this.events = events;
        this.clock = clock;
    }

    /**
     * @param leaseToken 租约令牌<b>明文</b>，走请求头传入（同 submit）——
     *                   放请求头而不是请求体，令牌就不会出现在请求日志的 body 里
     * @param reason     机娘自述的失败原因，进 {@code error_report.summary}
     */
    @Transactional
    public TicketStatusView reportFailure(AgentIdentity principal, String ticketCode,
                                          String leaseToken, String reason) {
        Instant now = Instant.now(clock);
        byte[] digest = tokenService.digest(leaseToken);

        // ① ★闸门★ 凭租约摘要认人：ACTIVE → FAILED_CLIENT。
        //    与 submit 同构——先原子改，改不动才回头问为什么。
        if (ownerMapper.markAttemptFailedByClient(digest, now) == 0) {
            throw rejectionFor(digest);
        }
        // ——— 这条 attempt 独占归我 ———

        // ② 取上下文并做行级授权。
        ContributionAttemptDO attempt = attemptMapper.selectByLeaseDigest(digest);
        ContributionTicketDO ticket = ticketMapper.selectById(attempt.getTicketId());
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        if (session == null || !Objects.equals(session.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        // 路径里的 ticketCode 与租约必须指向同一张票，否则就是拿 A 的租约报 B 的失败。
        if (!Objects.equals(ticket.getTicketCode(), ticketCode)) {
            throw new ApiException(ApiStatus.ACPP_LEASE_INVALID);
        }

        // ③ 失败沿因果链传播（与 L17 Worker 共用同一段逻辑）。
        boolean firstTurn = failurePropagation.propagate(ticket, session, now);

        // ④ 发事件：error_report 与 audit_record 由各模块的监听器写（事务性发件箱保证不丢）。
        events.publishEvent(new AttemptFailedByClient(
                attempt.getId(), ticket.getId(), session.getId(), session.getOwnerUserId(),
                ticket.getRequiredAgentId(), ticket.getSequenceNo(), firstTurn, reason, now));

        log.info("机娘 {} 自报第 {} 棒失败（ticket={}）：{}",
                principal.agentAccountId(), ticket.getSequenceNo(), ticketCode, reason);

        // 回读最新状态给机娘——让它一眼看出「这一棒已经作废，接下来等主人」。
        ContributionTicketDO after = ticketMapper.selectByCode(ticketCode);
        return TicketStatusView.from(after, attempt.getAttemptNo(), null);
    }

    /** 闸门 0 行 → 翻译。与 submit 的三种落地情形一致。 */
    private ApiException rejectionFor(byte[] digest) {
        ContributionAttemptDO current = attemptMapper.selectByLeaseDigest(digest);
        if (current == null) {
            return new ApiException(ApiStatus.ACPP_LEASE_INVALID);      // 403 令牌伪造/打错
        }
        // 已经不是 ACTIVE：可能自己报过了、可能 Worker 先收了尸、也可能它其实已经提交成功了。
        return new ApiException(ApiStatus.ACPP_TICKET_NOT_WRITABLE);    // 409 查席位状态
    }
}
