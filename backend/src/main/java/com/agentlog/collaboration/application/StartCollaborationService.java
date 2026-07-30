package com.agentlog.collaboration.application;

import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.api.dto.response.TicketView;
import com.agentlog.collaboration.domain.HandoffStatus;
import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.domain.TicketCodes;
import com.agentlog.collaboration.domain.TicketStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.HandoffTokenDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ChannelExistsMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.ContributionTicketMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.HandoffTokenMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.AgentIdentity;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开局：建协作会话 + 首棒席位 + 第一根悬空接力棒（L15 · TX 对应 Pack transaction-boundaries 的 start 段）。
 *
 * ★ 本课的问题框定：两个 AI 对话之间【零共享上下文】，主人是唯一的传递媒介。
 *   start 做的事就是「开一条因果链，并把链尾那根棒子交到主人手上」。
 *
 * ★ 三步的顺序被外键钉死，不能换：
 *   session（无依赖）→ ticket#1（FK 指向 session）→ handoff T1（FK 指向 ticket#1）
 *   最后再 updateById 把 session.tail 指向 T1——这条「回指」正是 V012 里那对循环外键的由来。
 *
 * ★ 本课【不】创建 post / draft：
 *   APPROVAL_RECORD 冻结了「首棒失败不暴露空草稿」。title/channelId 暂存进 planned_*，
 *   等首棒 submit（L16）成功时才据此创建 post+draft。
 *   于是「首棒失败」时 session 直接 INVALIDATED，库里从来没出现过那篇空草稿——
 *   不变量由【数据的存在性】保证，而不是靠每个读路径记得过滤"零块草稿"。
 */
@Service
public class StartCollaborationService {

    private final CollaborationSessionMapper sessionMapper;
    private final ContributionTicketMapper ticketMapper;
    private final HandoffTokenMapper handoffTokenMapper;
    private final ChannelExistsMapper channelExistsMapper;
    private final TokenService tokenService;
    private final TokenProperties tokenProperties;
    private final Clock clock;

    public StartCollaborationService(CollaborationSessionMapper sessionMapper,
                                    ContributionTicketMapper ticketMapper,
                                    HandoffTokenMapper handoffTokenMapper,
                                    ChannelExistsMapper channelExistsMapper,
                                    TokenService tokenService,
                                    TokenProperties tokenProperties,
                                    Clock clock) {
        this.sessionMapper = sessionMapper;
        this.ticketMapper = ticketMapper;
        this.handoffTokenMapper = handoffTokenMapper;
        this.channelExistsMapper = channelExistsMapper;
        this.tokenService = tokenService;
        this.tokenProperties = tokenProperties;
        this.clock = clock;
    }

    @Transactional
    public StartCollaborationResponse start(AgentIdentity principal, StartCollaborationRequest request) {
        Instant now = Instant.now(clock);

        // ① 分区必须存在。跨模块只读投影直查 forum_channel（守 D-05，不 import content 的 Mapper）。
        //    虽然 planned_channel_id 有外键、不校验也插不进去，但那样抛的是约束异常（500），
        //    而不是干净的 404 —— 语义层的错误要在语义层报。
        if (!channelExistsMapper.existsById(request.channelId())) {
            throw new ApiException(ApiStatus.CHANNEL_NOT_FOUND);
        }

        // ② 建会话。owner 取【机娘背后的主人】——与 L14 草稿归属同一个键，天然对齐既有租户隔离：
        //    主人在自己的协作列表里能看到它（L17 时间线页）。
        CollaborationSessionDO session = new CollaborationSessionDO();
        session.setPostTicket(TicketCodes.newPostTicket());
        session.setOwnerUserId(principal.ownerUserId());
        session.setPlannedTitle(request.title());
        session.setPlannedChannelId(request.channelId());
        session.setPlannedSummary(request.summary());
        session.setStatus(SessionStatus.OPEN.getCode());   // 本课的终点：排好队就停在 OPEN
        session.setLastCompletedSequence(0);
        session.setVersion(0L);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        // ③ 建首棒席位。sequence_no 从 1 起，无前序（predecessor = null）→ 直接 READY_TO_WRITE。
        //    作者维度三件套全部来自 agent 令牌，客户端传不进来（防伪造）。
        ContributionTicketDO firstTicket = newTicket(
                session.getId(), 1, principal, /* predecessorTicketId */ null,
                TicketStatus.READY_TO_WRITE, now);
        ticketMapper.insert(firstTicket);

        // ④ 签发第一根悬空尾令牌，指向首棒（「首棒之后」的资格）。
        HandoffIssue issue = issueTailToken(session, firstTicket.getId(), now);

        // ⑤ 会话回指链尾。这条回指与 handoff_token.session_id 互为环——
        //    正是 V012 里必须「先建表、后 ALTER 补外键」的原因。
        session.setTailHandoffTokenId(issue.tokenId());
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);

        return new StartCollaborationResponse(
                session.getPostTicket(),
                TicketView.from(firstTicket),
                issue.rawToken(),          // ★ 明文只在这里出现一次，库里只有摘要
                issue.expiresAt());
    }

    /**
     * 建席位的公共内核。owner/agent 两条投稿路的作者维度参数化思路同 L14 的 DraftAuthor，
     * 但这里只有 agent 一条路——机娘才能参与 ACPP，主人不排队（他直接建草稿）。
     */
    ContributionTicketDO newTicket(Long sessionId, int sequenceNo, AgentIdentity principal,
                                   Long predecessorTicketId, TicketStatus status, Instant now) {
        ContributionTicketDO ticket = new ContributionTicketDO();
        ticket.setTicketCode(TicketCodes.newTicketCode());
        ticket.setSessionId(sessionId);
        ticket.setSequenceNo(sequenceNo);
        // ★「这一棒只能由这个机娘来写」——服务端从令牌派生，不接受客户端声明。
        //   这一列是为 L18「retry 只允许原机娘」存在的。
        ticket.setRequiredAgentId(principal.agentAccountId());
        ticket.setSourceTool(principal.sourceTool());
        ticket.setClientRunId(principal.clientRunId());
        ticket.setPredecessorTicketId(predecessorTicketId);
        ticket.setStatus(status.getCode());
        ticket.setVersion(0L);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);
        return ticket;
    }

    /**
     * 签发一根新的悬空尾令牌（AVAILABLE），指向 predecessorTicketId 这一棒之后的接力资格。
     *
     * 明文由 TokenService 生成（前缀 handoff_，同 09-security/security-blueprint.md 的格式约定），
     * 库里只存 HMAC-SHA256(pepper, 明文) 摘要——脱库拿不到明文，无法伪造接力。
     * 明文由调用方放进响应，之后服务端再也拿不出来。
     */
    HandoffIssue issueTailToken(CollaborationSessionDO session, Long predecessorTicketId, Instant now) {
        String rawToken = tokenService.generateRawToken("handoff_");
        Instant expiresAt = now.plus(tokenProperties.getHandoffTtl());   // 默认 24h

        HandoffTokenDO token = new HandoffTokenDO();
        token.setTokenDigest(tokenService.digest(rawToken));
        token.setSessionId(session.getId());
        // 冗余存主人：消费时一条查询就能做行级授权，不必 join 回 session。
        token.setOwnerUserId(session.getOwnerUserId());
        token.setPredecessorTicketId(predecessorTicketId);
        token.setStatus(HandoffStatus.AVAILABLE.getCode());
        token.setExpiresAt(expiresAt);
        token.setVersion(0L);
        token.setCreatedAt(now);
        handoffTokenMapper.insert(token);

        return new HandoffIssue(token.getId(), rawToken, expiresAt);
    }

    /** 签发结果：库里那行的 id + 只此一次的明文 + 失效时刻。 */
    record HandoffIssue(Long tokenId, String rawToken, Instant expiresAt) {
    }
}
