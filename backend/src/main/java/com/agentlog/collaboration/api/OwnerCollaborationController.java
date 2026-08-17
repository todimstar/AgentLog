package com.agentlog.collaboration.api;

import com.agentlog.collaboration.api.dto.response.CollaborationDetailView;
import com.agentlog.collaboration.api.dto.response.ReissueHandoffResponse;
import com.agentlog.collaboration.api.dto.response.RetryTicketResponse;
import com.agentlog.collaboration.api.dto.response.StopCollaborationResponse;
import com.agentlog.collaboration.application.CollaborationTimelineService;
import com.agentlog.collaboration.application.ReissueHandoffService;
import com.agentlog.collaboration.application.RetryTicketService;
import com.agentlog.collaboration.application.StopCollaborationService;
import com.agentlog.collaboration.infrastructure.persistence.mapper.OwnerCollaborationMapper;
import com.agentlog.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主人的协作决策入口（L18 · <b>Chain 1</b> 保护：Session + CSRF，路径 {@code /api/v1/owner/**}）。
 *
 * <h3>★ 本项目第一组「人的决策」端点</h3>
 * L15/L16/L17 的协作端点全在 <b>Chain 3</b>（机娘的 Bearer 令牌）。本控制器是第一次由
 * <b>浏览器里的主人</b>驱动协作状态机——机娘的令牌<b>根本进不来这条链</b>。
 * <pre>
 *   GET  /owner/collaboration-sessions/{postTicket}                          协作时间线（本课唯一只读）
 *   POST /owner/collaboration-sessions/{postTicket}/tickets/{code}/retry      重试某一棒
 *   POST /owner/collaboration-sessions/{postTicket}/stop                      结束协作（唯一出口）
 *   POST /owner/collaboration-sessions/{postTicket}/handoff                   重新签发尾令牌
 * </pre>
 *
 * <h3>★ 为什么这些端点【不走 CollaborationFacade】</h3>
 * L17 的 {@code CollaborationFacade} 注释里写着「这个分工也让 L18 的 retry 有现成的落点」，
 * 很容易被误读成"retry 要挂到 Facade 上"。<b>不需要</b>——
 * Facade 是<b>给别的模块调</b>的（reliability 的 Worker 在模块外，够不着私有子包）。
 * 而本控制器<b>本来就在 collaboration 模块内部</b>，直接调 Service 即可。
 * <p>那句注释真正的意思是：<b>「状态机推进这件事归 collaboration」这个分工是对的，
 * retry 属于同一类，所以它也该写在这个模块里</b>——而不是"必须走 Facade 那道门"。
 *
 * <h3>★ 为什么四个端点都【没有】{@code @Idempotent}</h3>
 * 每一个动作的<b>闸门本身就是幂等</b>：条件 UPDATE 影响 0 行 = 这件事已经发生过了。
 * 而幂等键<b>由客户端生成</b>——浏览器点两次是两个不同的 key，幂等根本认不出它们是同一件事。
 * <p>★ 判据：<b>幂等防的是「同一个请求被重发」，闸门防的是「这件事被重复执行」——
 * 不管来的是不是同一个请求。</b>先问「重发会不会造成第二次真实的改变」，会才需要幂等。
 *
 * <h3>★ 路径里为什么有 postTicket 又有 ticketCode（retry 那条）</h3>
 * {@code ticket_code} 上有全局唯一键，单独一个就能查到票——<b>技术上确实冗余</b>。
 * 保留是为了守契约与 URL 自解释，<b>但服务端必须真的校验「这张票属于这个会话」</b>：
 * <b>一个不被检查的参数比没有这个参数更糟</b>，它会制造「系统检查过了」的错觉。
 */
@RestController
@RequestMapping("/api/v1/owner/collaboration-sessions")
public class OwnerCollaborationController {

    private final CollaborationTimelineService timelineService;
    private final RetryTicketService retryTicketService;
    private final StopCollaborationService stopCollaborationService;
    private final ReissueHandoffService reissueHandoffService;
    private final OwnerCollaborationMapper ownerMapper;

    public OwnerCollaborationController(CollaborationTimelineService timelineService,
                                        RetryTicketService retryTicketService,
                                        StopCollaborationService stopCollaborationService,
                                        ReissueHandoffService reissueHandoffService,
                                        OwnerCollaborationMapper ownerMapper) {
        this.timelineService = timelineService;
        this.retryTicketService = retryTicketService;
        this.stopCollaborationService = stopCollaborationService;
        this.reissueHandoffService = reissueHandoffService;
        this.ownerMapper = ownerMapper;
    }

    /**
     * 协作时间线：席位全景 + 动作流水 + 事故报告（含建议动作）。
     *
     * <p>★ 它是 {@code collaboration_session.status} 的<b>第一个真正消费者</b>——
     * 在此之前那个字段被写 4 处、读来做判定 0 处，所以 L16 那个
     * 「{@code AWAITING_CONTINUATION → RUNNING} 没实现」的 bug 才能潜伏两课。
     */
    @GetMapping("/{postTicket}")
    public CollaborationDetailView detail(@PathVariable String postTicket) {
        return timelineService.get(CurrentUser.requireId(), postTicket);
    }

    /**
     * 重试某一棒：票改回可写、解冻整条尾巴与尾令牌、会话回到「等人来接」。
     *
     * <p>★ 为什么必须由人点这个按钮：机娘的对话已经崩了，服务端与它之间是<b>「拉」不是「推」</b>——
     * 它连对方还在不在都不知道。自动重试只会把票改回可写、再超时、再重试 = 死循环。
     * <b>这一步需要的信息只存在于系统之外</b>（你还想不想继续、能不能把那个 AI 叫回来）。
     */
    @PostMapping("/{postTicket}/tickets/{ticketCode}/retry")
    public RetryTicketResponse retry(@PathVariable String postTicket,
                                     @PathVariable String ticketCode) {
        RetryTicketService.RetryOutcome outcome =
                retryTicketService.retry(CurrentUser.requireId(), postTicket, ticketCode);
        // 展示名在表现层补：Service 只管状态机推进，不必知道"页面要显示什么"。
        String nickname = outcome.requiredAgentId() == null
                ? null : ownerMapper.selectAgentNickname(outcome.requiredAgentId());
        return new RetryTicketResponse(
                outcome.ticketCode(), outcome.ticketStatus(), outcome.sequenceNo(),
                outcome.requiredAgentId(), nickname,
                outcome.unblockedCount(), outcome.handoffUnfrozen());
    }

    /**
     * 结束协作——<b>唯一出口</b>，把草稿的控制权还给主人。
     *
     * <p>⚠️ 它<b>不删任何内容</b>。协作运行中草稿只读（{@code DRAFT_LOCKED_BY_COLLAB}），
     * 这个动作的本质是<b>解锁</b>，不是放弃。内容是删是留归草稿模块（L19）管——
     * <b>别让一个机制回答两个问题。</b>
     */
    @PostMapping("/{postTicket}/stop")
    public StopCollaborationResponse stop(@PathVariable String postTicket) {
        StopCollaborationService.StopOutcome outcome =
                stopCollaborationService.stop(CurrentUser.requireId(), postTicket);
        return new StopCollaborationResponse(
                outcome.postTicket(), outcome.sessionStatus(), outcome.cancelledTicketCount(),
                outcome.revokedRunningAttempt(), outcome.handoffRevoked());
    }

    /**
     * 重新签发尾令牌：旧的作废，新的<b>明文只在这次响应里出现一次</b>。
     *
     * <p>★ UX 规格要的是「查看下一棒尾令牌」，但库里只有 HMAC 摘要、<b>算不回明文</b>——
     * 「查看」物理上不可能。判据：<b>UX 需求撞上安全模型时，往往不是砍需求，
     * 而是换一个能满足它的机制</b>。
     *
     * <p>响应里带 {@code claimCommand}：主人一键复制，粘给新开的 AI 对话即可。
     * ★ <b>这就是「服务端怎么通知机娘」的正确形态——经由主人，且让主人零思考</b>，
     * 与 L15 接力棒必须经过人手复制粘贴是同一个架构决定。
     */
    @PostMapping("/{postTicket}/handoff")
    public ReissueHandoffResponse reissueHandoff(@PathVariable String postTicket) {
        ReissueHandoffService.ReissueOutcome outcome =
                reissueHandoffService.reissue(CurrentUser.requireId(), postTicket);
        return new ReissueHandoffResponse(
                outcome.postTicket(), outcome.handoffToken(), outcome.expiresAt(),
                "agentlog collab join --handoff " + outcome.handoffToken());
    }
}
