package com.agentlog.collaboration.application;

import com.agentlog.collaboration.application.StartCollaborationService.HandoffIssue;
import com.agentlog.collaboration.domain.SessionStatus;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.CollaborationSessionDO;
import com.agentlog.collaboration.infrastructure.persistence.dataobject.HandoffTokenDO;
import com.agentlog.collaboration.infrastructure.persistence.mapper.CollaborationSessionMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.HandoffTokenMapper;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.event.HandoffReissued;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 重新签发尾令牌（L18，还 D-16 的遗留）。
 *
 * <h3>★ 为什么是「重新签发」而不是「重新查看」——本课最反直觉的一点</h3>
 * UX 规格 {@code 06-web/owner-review-ux.md} 第 6 行要求「可查看下一棒尾令牌」。
 * <b>这件事物理上做不到</b>：
 * <pre>
 *   handoff_token 表里存的是  token_digest BINARY(32) = HMAC-SHA256(pepper, 明文)
 *   摘要是【单向】的：有明文能算出摘要，有摘要【永远】算不回明文。
 *
 *   明文的一生：签发那一刻放进 HTTP 响应 → 主人复制走 → 服务端内存里那份被 GC 回收
 *                                                      ↓
 *                                          ★ 从此世上只有主人手里那一份 ★
 * </pre>
 * L16 已经为这条铁律付过一次代价（D-16 第 2 条：submit 响应的 {@code nextHandoffToken}
 * <b>恒为 null</b>，因为拿不回明文）。
 * <p>★ 判据：<b>当 UX 需求撞上安全模型时，往往不是砍需求，而是换一个能满足它的机制。</b>
 * 主人真正要的是「我能拿到一根可用的令牌」，不是「我要看那一根特定的令牌」。
 *
 * <h3>★ 为什么存摘要而不是明文（既然 CLI 每次都把明文发过来）</h3>
 * 存摘要防的<b>不是传输被看到</b>，而是<b>数据库被脱库</b>：
 * 攻击者拿到 dump，若是明文就能直接冒充任何机娘 join 任何协作；
 * 是摘要则需要 pepper，而 pepper 在<b>应用配置</b>里（{@code AGENTLOG_TOKEN_PEPPER}），不在库里。
 * ⇒ <b>纵深防御：数据库与应用配置是两个独立的攻击面，攻破一个不足以拿到凭证。</b>
 * 同密码存 bcrypt——你登录时也发明文，但库里绝不能存明文。
 *
 * <h3>★ 什么时候用得上（第三条最关键）</h3>
 * ① 主人把令牌弄丢了（关掉了显示它的页面）——最常见；
 * ② 怀疑泄漏，想作废重发；
 * ③ 🔴 <b>retry 刚把尾令牌从 FROZEN 解冻回 AVAILABLE，但主人手上早就没有那串明文了</b>
 *    ⇒ 重签是 retry 的天然搭档，这也是 D-16 把它排进 L18 的原因。
 *
 * <h3>★ 闸门为什么落在【旧令牌】上</h3>
 * 闸门要选那个「<b>能唯一代表这次操作发生过</b>」的对象。
 * 重签前后 session 的状态<b>不变</b>、链尾票的状态也<b>不变</b>——它们记不住"重签发生过"，
 * 两个并发请求会双双通过、签出两根令牌。
 * 而旧令牌的 status 从 {@code AVAILABLE/FROZEN → REVOKED}，<b>这个变化本身就是那条记录</b>。
 *
 * <p>⚠️ 旧令牌<b>必须真的吊销</b>，不能只是"不管它"：那串明文若已流到别人手里
 * （比如被粘进公开的聊天记录），不吊销就等于留了一个可用的后门。
 */
@Service
public class ReissueHandoffService {

    private static final Logger log = LoggerFactory.getLogger(ReissueHandoffService.class);

    /** 允许重签的会话状态：协作还没结束就行（暂停中也允许——那正是场景③）。 */
    private static final Set<String> REISSUABLE = Set.of(
            SessionStatus.OPEN.getCode(),
            SessionStatus.RUNNING.getCode(),
            SessionStatus.AWAITING_CONTINUATION.getCode(),
            SessionStatus.PAUSED_ON_ERROR.getCode());

    private final OwnerCollaborationMapper ownerMapper;
    private final CollaborationSessionMapper sessionMapper;
    private final HandoffTokenMapper handoffTokenMapper;
    private final StartCollaborationService startService;   // 复用签发内核
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ReissueHandoffService(OwnerCollaborationMapper ownerMapper,
                                 CollaborationSessionMapper sessionMapper,
                                 HandoffTokenMapper handoffTokenMapper,
                                 StartCollaborationService startService,
                                 ApplicationEventPublisher events,
                                 Clock clock) {
        this.ownerMapper = ownerMapper;
        this.sessionMapper = sessionMapper;
        this.handoffTokenMapper = handoffTokenMapper;
        this.startService = startService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ReissueOutcome reissue(Long ownerUserId, String postTicket) {
        Instant now = Instant.now(clock);

        // ① 授权写进 SQL：不是你的 → 404。
        Long sessionId = ownerMapper.selectSessionIdByPostTicket(postTicket, ownerUserId);
        if (sessionId == null) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_FOUND);
        }
        CollaborationSessionDO session = sessionMapper.selectById(sessionId);
        if (!REISSUABLE.contains(session.getStatus())) {
            throw new ApiException(ApiStatus.ACPP_SESSION_NOT_ACTIVE);
        }

        Long oldTokenId = session.getTailHandoffTokenId();
        if (oldTokenId == null) {
            // 理论上不会发生（start 时必然签发一根），防御性处理。
            throw new ApiException(ApiStatus.ACPP_HANDOFF_NOT_FOUND);
        }
        HandoffTokenDO oldToken = handoffTokenMapper.selectById(oldTokenId);

        // ② ★闸门★ 吊销旧的那根。affectedRows=0 说明它已被消费或已被吊销——局面变了，别硬来。
        if (ownerMapper.revokeHandoffById(oldTokenId) == 0) {
            throw new ApiException(ApiStatus.ACPP_HANDOFF_CONSUMED);
        }
        // ——— 独占归我，下面无竞争 ———

        // ③ 签发新的一根，指向【同一个前序】——链尾的位置没变，变的只是那张凭证。
        //    复用 start 的签发内核：明文只在返回值里出现一次，库里只有摘要。
        HandoffIssue issue = startService.issueTailToken(
                session, oldToken.getPredecessorTicketId(), now);

        // ④ 会话链尾指向新令牌。
        ownerMapper.updateTailHandoff(sessionId, issue.tokenId(), now);

        events.publishEvent(new HandoffReissued(
                sessionId, ownerUserId, oldTokenId, issue.tokenId(),
                oldToken.getPredecessorTicketId(), now));

        log.info("主人 {} 为协作 {} 重新签发尾令牌：旧 #{} 已吊销，新 #{}",
                ownerUserId, postTicket, oldTokenId, issue.tokenId());

        return new ReissueOutcome(postTicket, issue.rawToken(), issue.expiresAt(),
                oldToken.getPredecessorTicketId());
    }

    /**
     * @param handoffToken 新令牌<b>明文</b>——★ 只在这一次响应里出现，之后永远拿不回来。
     */
    public record ReissueOutcome(String postTicket,
                                 String handoffToken,
                                 Instant expiresAt,
                                 Long predecessorTicketId) {
    }
}
