package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.api.dto.response.TicketView;
import com.agentlog.collaboration.application.StartCollaborationService.HandoffIssue;
import com.agentlog.collaboration.domain.HandoffStatus;
import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.HandoffTokenDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.HandoffTokenMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.HandoffClaimed;
import com.agentlog.shared.security.AgentIdentity;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接力：原子消费一根接力棒，往因果链尾部追加一个席位，并签发新的悬空尾令牌（L15 · TX-03）。
 *
 * ★★★ 本课的皇冠。全部难点集中在【执行顺序】上：
 * <pre>
 *   ① 查令牌（只取上下文，不做判定）
 *   ② ★原子消费 —— 闸门在这里，唯一的裁决者★
 *   ③ 建新席位（到这里已经独占，零竞争）
 *   ④ 回填 consumed_ticket_id
 *   ⑤ 签发新尾令牌 + 会话链尾回指
 * </pre>
 *
 * ★ ① 的 SELECT 会不会又变成「查-判-改」三步走？——不会，区别在于【判定权归谁】：
 *   三步走的错在于「拿 SELECT 的结果做判定，然后 UPDATE 假设那个判定仍然成立」；
 *   这里的 SELECT 只是【取数据】（要往哪个 session 插票、前序是哪一棒），
 *   判定 100% 由 ② 那条 UPDATE 的 WHERE 完成。
 *   即使 ① 到 ② 之间令牌被别人抢走，② 一定返回 0，我们照样失败。**判定权始终在那条 UPDATE 手里。**
 *
 * ★ 为什么闸门必须在建票【之前】（实现期发现的坑，值得记住）：
 *   若顺序是「查 → 建票 → 消费」，两个线程抢同一张令牌时会【双双先建票】——
 *   此时还没人被拦，sequence_no 都算成同一个值 → 第二个撞 uk_ticket_sequence 抛 DuplicateKeyException，
 *   败者拿到的是 500 而不是干净的 409 +「向主人索取新尾令牌」。
 *   ——靠唯一键兜底就是「用异常控制业务流程」。闸门优先之后，
 *   uk_ticket_sequence 退回它该有的角色：最后的安全网，正常路径永不触发。
 */
@Service
public class ClaimHandoffService {

    private final CollaborationSessionMapper sessionMapper;
    private final ContributionTicketMapper ticketMapper;
    private final HandoffTokenMapper handoffTokenMapper;
    private final StartCollaborationService startService;   // 复用建票与签发尾令牌的内核
    private final TokenService tokenService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ClaimHandoffService(CollaborationSessionMapper sessionMapper,
                              ContributionTicketMapper ticketMapper,
                              HandoffTokenMapper handoffTokenMapper,
                              StartCollaborationService startService,
                              TokenService tokenService,
                              ApplicationEventPublisher events,
                              Clock clock) {
        this.sessionMapper = sessionMapper;
        this.ticketMapper = ticketMapper;
        this.handoffTokenMapper = handoffTokenMapper;
        this.startService = startService;
        this.tokenService = tokenService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public StartCollaborationResponse claim(AgentIdentity principal, ClaimHandoffRequest request) {
        Instant now = Instant.now(clock);

        // 明文 → HMAC 摘要。库里只有摘要，所以查找也按摘要走（走 uk_handoff_digest 点查）。
        byte[] digest = tokenService.digest(request.handoffToken());

        // ① 取上下文。注意：这一步【不做状态判定】，判定全交给 ② 那条 UPDATE。
        HandoffTokenDO token = handoffTokenMapper.selectByDigest(digest);
        if (token == null) {
            throw new ApiException(ApiStatus.ACPP_HANDOFF_NOT_FOUND);
        }
        // 多租户行级授权：这张令牌不属于我的主人 → 一律 404（不是 403）。
        // 403 等于承认"它存在、只是不给你"，会泄漏资源存在性；见 Pack 多租户授权与行级隔离 §5。
        if (!Objects.equals(token.getOwnerUserId(), principal.ownerUserId())) {
            throw new ApiException(ApiStatus.ACPP_HANDOFF_NOT_FOUND);
        }

        // ② ★ 闸门：一条带条件的 UPDATE 完成 check + act。affectedRows 就是裁决书。
        int affected = handoffTokenMapper.consumeAvailableToken(digest, principal.agentAccountId(), now);
        if (affected == 0) {
            // 没抢到。此时才回查判定【为什么】——先原子改、改不动才问原因，顺序反了就又是三步走。
            throw rejectionFor(digest, now);
        }
        // ——— 走到这里，这张令牌已经独占归我。下面所有步骤没有任何竞争者。———

        // ③ 建新席位。棒次 = 前序棒次 + 1；前序就是这张令牌所指的那一棒（因果链，不看时间）。
        ContributionTicketDO predecessor = ticketMapper.selectById(token.getPredecessorTicketId());
        if (predecessor == null) {
            // 令牌的 predecessor_ticket_id 是 NOT NULL 且有外键，理论上不可能查不到。
            // 真出现说明数据被外部改坏了，宁可显式失败也不要静默建出一条断链的票。
            throw new ApiException(ApiStatus.ACPP_HANDOFF_NOT_FOUND);
        }

        // ★ 前序没写完 → 新棒只能【排队】不能写：APPROVAL_RECORD 的「后序可提前排队，不可提前写」。
        //   这正是接力的价值：第二个 AI 现在就能占住位置，不必等第一个写完才来。
        boolean predecessorDone = TicketStatus.DONE.getCode().equals(predecessor.getStatus());
        TicketStatus newStatus = predecessorDone
                ? TicketStatus.READY_TO_WRITE
                : TicketStatus.WAITING_PREDECESSOR;

        ContributionTicketDO newTicket = startService.newTicket(
                token.getSessionId(),
                predecessor.getSequenceNo() + 1,
                principal,                       // ★ required_agent_id = 消费令牌的这个机娘（实名化的那一刻）
                predecessor.getId(),
                newStatus,
                now);
        ticketMapper.insert(newTicket);

        // ④ 回填「这张令牌换出了哪张席位」。按主键更新，无竞争（详见 Mapper 的 javadoc）。
        handoffTokenMapper.linkConsumedTicket(token.getId(), newTicket.getId());

        // ⑤ 签发新的悬空尾令牌（指向刚建的这一棒），并把会话链尾指过去。
        //    于是链又有了一根可交给下一个 AI 的棒子——接力可以无限延长。
        CollaborationSessionDO session = sessionMapper.selectById(token.getSessionId());
        HandoffIssue issue = startService.issueTailToken(session, newTicket.getId(), now);
        // L18 改用精准 UPDATE（只碰链尾指针一列），理由同 StartCollaborationService。
        sessionMapper.updateTailHandoffToken(session.getId(), issue.tokenId(), now);
        session.setTailHandoffTokenId(issue.tokenId());

        // ⑥ 发 HandoffClaimed 事件（L18 补的欠账，同 COLLAB_STARTED / LEASE_CLAIMED）。
        //    它是时间线上「谁在谁之后接的棒」这条因果链的可读形态——
        //    链本身存在 predecessor_ticket_id 里，那是给机器看的，时间线要给人看。
        events.publishEvent(new HandoffClaimed(
                session.getId(), session.getOwnerUserId(), principal.agentAccountId(),
                newTicket.getId(), newTicket.getSequenceNo(),
                newTicket.getPredecessorTicketId(), now));

        return new StartCollaborationResponse(
                session.getPostTicket(),
                TicketView.from(newTicket),
                issue.rawToken(),
                issue.expiresAt());
    }

    /**
     * 把「消费返回 0 行」翻译成具体的错误码，让客户端能按 error-actions.md 自愈。
     *
     * 四种落地情形（回查那一行的状态与时效）：
     *   CONSUMED  → 409，已被别人（或自己重放）用掉 → 向主人索取最新尾令牌
     *   FROZEN    → 409，前序失败链已冻结（L17）→ 停止并报告主人
     *   REVOKED   → 409，协作已终止/已发布（L18/L20）
     *   已过期     → 410，曾经有效现已永久失效 → 索取新尾令牌
     *
     * ⚠️ 回查到的状态**可能与消费失败的真实原因不同**（比如回查瞬间又被别人改了）。
     *    这不影响正确性——我们已经确定"你没抢到"，回查只为给出更有帮助的提示。
     *    绝不能反过来：拿回查结果当判定依据。
     */
    private ApiException rejectionFor(byte[] digest, Instant now) {
        HandoffTokenDO current = handoffTokenMapper.selectByDigest(digest);
        if (current == null) {
            return new ApiException(ApiStatus.ACPP_HANDOFF_NOT_FOUND);
        }
        String status = current.getStatus();
        if (HandoffStatus.FROZEN.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_HANDOFF_FROZEN);
        }
        if (HandoffStatus.REVOKED.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_HANDOFF_REVOKED);
        }
        if (HandoffStatus.CONSUMED.getCode().equals(status)) {
            return new ApiException(ApiStatus.ACPP_HANDOFF_CONSUMED);
        }
        // 还挂着 AVAILABLE/EXPIRED 却抢不到 → 只可能是过期（WHERE 里的 expires_at 没通过）。
        // 注意这里能走到，恰好证明了「过期判定不依赖清理 Worker」：
        // 状态可能还是 AVAILABLE（Worker 没来得及标 EXPIRED），但消费一定失败。
        if (current.getExpiresAt() != null && current.getExpiresAt().isBefore(now)) {
            return new ApiException(ApiStatus.ACPP_HANDOFF_EXPIRED);
        }
        // 兜底：状态与时效都看不出问题。
        // ⚠️ L16 更正（原注释写的是「极罕见（如并发窗口内又被改回）」，理由是错的）：
        //   这在并发路径下【是必然而非罕见】——闸门失败后的这次回查读到的是【事务快照】，
        //   MySQL 默认 REPEATABLE READ，赢家的 UPDATE 尚未提交时，败者看到的那一行仍是 AVAILABLE。
        //   本处结论（按已消费处理）恰好正确，所以 L15 的并发测试没能发现理由写错了；
        //   L16 的 ClaimLeaseService 第一版照抄这个思路，兜底值选得不同，当场被并发测试打红。
        //   正确的推理是：闸门是唯一裁决者，它说 0 行就是 0 行；回查显示"看起来能用"，
        //   唯一解释就是并发有人先消费了、只是我还看不见 —— 与看到 CONSUMED 是同一件事。
        return new ApiException(ApiStatus.ACPP_HANDOFF_CONSUMED);
    }
}
