package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.request.SubmitContributionRequest;
import com.agentlog.collaboration.domain.AttemptStatus;
import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionAttemptDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionAttemptMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.content.ContentFacade;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.ContributionSubmitted;
import com.agentlog.shared.security.AgentIdentity;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交一棒贡献：把机娘写的正文落进草稿，席位交棒给下一个（L16 · TX-05）。
 *
 * <h3>★ 执行顺序</h3>
 * <pre>
 *   ①（幂等由 @Idempotent 切面在 Controller 层处理，这里看不见它）
 *   ② ★原子闸门 —— 消费租约，唯一的裁决者，排在写内容【之前】★
 *   ③ 取上下文并做三重身份校验（票 / 机娘 / 主人）
 *   ④ 跨模块写 content —— ContentFacade，同一事务
 *   ⑤ 首棒回填 session.post_id / draft_id
 *   ⑥ 席位 → DONE
 *   ⑦ ★唤醒直接后继：WAITING_PREDECESSOR → READY_TO_WRITE
 *   ⑧ 会话 → AWAITING_CONTINUATION，推进 last_completed_sequence
 * </pre>
 *
 * <h3>★ 闸门为什么排在写内容之前</h3>
 * 同 L15/claim lease 的理由：先确定"这次提交归我"，后面每一步才没有竞争者。
 * 若反过来（先写 content 再改 attempt），两个重放的请求会双双写出正文，
 * 最后才在 {@code uk_contribution_ticket} 上撞车——又是拿唯一键当闸门。
 *
 * <h3>★ 闸门先行会不会导致"标了成功却没写成"</h3>
 * 不会：闸门与写内容<b>在同一个事务里</b>。content 写失败 → 整个事务回滚 →
 * attempt 退回 ACTIVE，租约还在机娘手里，可以原样重试。
 * 这也是 {@link ContentFacade#appendAgentContribution} 用 {@code Propagation.MANDATORY} 的原因——
 * 它拒绝在没有事务的情况下被调用，把「必须同事务」交给容器强制，而不是靠注释提醒。
 */
@Service
public class SubmitContributionService {

    private final ContributionAttemptMapper attemptMapper;
    private final ContributionTicketMapper ticketMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final ContentFacade contentFacade;
    private final TokenService tokenService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SubmitContributionService(ContributionAttemptMapper attemptMapper,
                                     ContributionTicketMapper ticketMapper,
                                     CollaborationSessionMapper sessionMapper,
                                     ContentFacade contentFacade,
                                     TokenService tokenService,
                                     ApplicationEventPublisher events,
                                     Clock clock) {
        this.attemptMapper = attemptMapper;
        this.ticketMapper = ticketMapper;
        this.sessionMapper = sessionMapper;
        this.contentFacade = contentFacade;
        this.tokenService = tokenService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public SubmitOutcome submit(AgentIdentity principal, String ticketCode,
                                String leaseToken, SubmitContributionRequest request) {
        Instant now = Instant.now(clock);
        byte[] leaseDigest = tokenService.digest(leaseToken);

        // ② ★ 闸门：租约还有效吗 + 标记完成，一条 UPDATE 做完。affectedRows 就是裁决书。
        int affected = attemptMapper.consumeActiveLease(leaseDigest, now);
        if (affected == 0) {
            throw leaseRejection(leaseDigest, now);
        }
        // ——— 走到这里，这次提交独占归我。———

        // ③ 取上下文 + 三重校验。
        ContributionAttemptDO attempt = attemptMapper.selectByLeaseDigest(leaseDigest);
        ContributionTicketDO ticket = ticketMapper.selectById(attempt.getTicketId());
        if (ticket == null) {
            // attempt.ticket_id 是 NOT NULL 且有外键，查不到说明数据被外部改坏了。
            // 宁可显式失败，也不要把内容写进一个来路不明的位置。
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }
        // 路径里的票 与 租约指向的票 必须是同一张。不一致说明客户端把两次协作的参数混用了。
        if (!Objects.equals(ticket.getTicketCode(), ticketCode)) {
            throw new ApiException(ApiStatus.ACPP_LEASE_INVALID);
        }
        // 拿着别人的租约来提交（同一主人名下的另一个机娘）→ 403，提示换回原机娘。
        if (!Objects.equals(ticket.getRequiredAgentId(), principal.agentAccountId())) {
            throw new ApiException(ApiStatus.ACPP_WRONG_AGENT);
        }
        CollaborationSessionDO session = sessionMapper.selectById(ticket.getSessionId());
        if (session == null || !Objects.equals(session.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_TICKET_NOT_FOUND);
        }

        // ④ ★ 跨模块写 content —— 本项目第一次通过 Facade 写别人的表（ADR-0006 · DRIFT D-16）。
        //    首棒（session.draftId == null）建 post+draft+contribution+block；后续棒只 append。
        //    ★ 为什么草稿要等到首棒 submit 才建（而不是 collab start 时）：
        //      APPROVAL_RECORD 冻结了「首棒失败不暴露空草稿」——不建那一行，任何读路径都查不到它。
        //      不变量由【数据的存在性】保证，而不是由每个查询都记得过滤来保证。
        ContentFacade.AppendResult appended = contentFacade.appendAgentContribution(
                new ContentFacade.AppendCommand(
                        session.getDraftId(),
                        principal.ownerUserId(),
                        principal.agentAccountId(),
                        principal.sourceTool(),
                        principal.clientRunId(),
                        session.getPlannedTitle(),
                        session.getPlannedChannelId(),
                        session.getPlannedSummary(),
                        request.content(),
                        session.getId(),
                        ticket.getId()));

        // ⑤ 首棒：把刚建出来的 post/draft 挂回会话。之后的每一棒都靠这两列找到该往哪写。
        if (session.getDraftId() == null) {
            session.setPostId(appended.postId());
            session.setDraftId(appended.draftId());
        }

        // ⑥ 席位落终态。
        ticketMapper.markDone(ticket.getId(), now);

        // ⑦ ★ 唤醒直接后继 ——「后序可提前排队，不可提前写」在这一刻兑现。
        //    下一个机娘的 collab wait 正在轮询，这条 UPDATE 让它下一次轮询看到 READY_TO_WRITE。
        ticketMapper.wakeSuccessor(ticket.getId(), now);

        // ⑧ 会话推进。写完这一棒之后【没有任何一棒在写】——因果链是串行的，
        //    后序必须等前序 DONE 才能领租约。所以状态一律落 AWAITING_CONTINUATION，
        //    等下一棒 claim lease 时再回到 RUNNING。
        session.setLastCompletedSequence(ticket.getSequenceNo());
        session.setStatus(SessionStatus.AWAITING_CONTINUATION.getCode());
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);

        // ⑨ 发布 ContributionSubmitted 事件（还 L16 TX-05 第 11 步的账）。
        //    Modulith 在同一事务里写进 EVENT_PUBLICATION 表，提交后异步投递给 AuditListener。
        boolean firstTurn = (ticket.getPredecessorTicketId() == null);
        events.publishEvent(new ContributionSubmitted(
                ticket.getId(), session.getId(), session.getOwnerUserId(),
                ticket.getRequiredAgentId(), appended.contributionId(),
                ticket.getSequenceNo(), firstTurn, now));

        return new SubmitOutcome(ticketCode, TicketStatus.DONE.getCode(), appended.draftId());
    }

    /**
     * 把「闸门返回 0 行」翻译成具体错误码。
     *
     * <p>三种落地情形：
     * <pre>
     *   查不到这个摘要        → 403 LEASE_INVALID  租约令牌是伪造的/打错的，停止并报告主人
     *   已 SUCCEEDED         → 409 ALREADY_CLAIMED 这一棒已经交过了（重放且台账已过期）→ 查席位状态
     *   ACTIVE 但已过期       → 410 LEASE_EXPIRED   写太久了，向主人申请 retry
     * </pre>
     *
     * <p>★ 注意「ACTIVE 但已过期」这一条能走到，恰好证明了<b>过期判定不依赖清理 Worker</b>：
     * 库里的 status 还挂着 ACTIVE（L17 的 Worker 还没来标记 FAILED_TIMEOUT），但闸门一定拦得住。
     * <b>状态不准 ≠ 行为不对。</b>
     */
    private ApiException leaseRejection(byte[] leaseDigest, Instant now) {
        ContributionAttemptDO current = attemptMapper.selectByLeaseDigest(leaseDigest);
        if (current == null) {
            return new ApiException(ApiStatus.ACPP_LEASE_INVALID);
        }
        if (AttemptStatus.SUCCEEDED.getCode().equals(current.getStatus())) {
            return new ApiException(ApiStatus.ACPP_LEASE_ALREADY_CLAIMED);
        }
        if (current.getLeaseExpiresAt() != null && current.getLeaseExpiresAt().isBefore(now)) {
            return new ApiException(ApiStatus.ACPP_LEASE_EXPIRED);
        }
        // FAILED_TIMEOUT（L17 已标记）/ REVOKED（L18 终止）/ FAILED_CLIENT：都已是终态。
        return new ApiException(ApiStatus.ACPP_LEASE_EXPIRED);
    }

    /**
     * 提交结果。返回 draftId 而不是 draftUrl —— URL 要拼 {@code agentlog.web.base-url}，
     * 那是表现层的配置，由 Controller 负责（同 L14 AgentDraftController 的分工）。
     */
    public record SubmitOutcome(String ticketCode, String ticketStatus, Long draftId) {
    }
}
