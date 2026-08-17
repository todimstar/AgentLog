package com.agentlog.collaboration.api;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.ReportFailureRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.request.SubmitContributionRequest;
import com.agentlog.collaboration.api.dto.response.ClaimLeaseResponse;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.api.dto.response.PrecedingContentView;
import com.agentlog.collaboration.api.dto.response.SubmitContributionResponse;
import com.agentlog.collaboration.api.dto.response.TicketStatusView;
import com.agentlog.collaboration.application.ClaimHandoffService;
import com.agentlog.collaboration.application.ClaimLeaseService;
import com.agentlog.collaboration.application.PrecedingContentService;
import com.agentlog.collaboration.application.ReportClientFailureService;
import com.agentlog.collaboration.application.StartCollaborationService;
import com.agentlog.collaboration.application.SubmitContributionService;
import com.agentlog.collaboration.application.TicketStatusService;
import com.agentlog.shared.idempotency.Idempotent;
import com.agentlog.shared.ratelimit.RateLimit;
import com.agentlog.shared.security.AgentIdentity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACPP 协作入口（L15/L16 · Chain 3 保护，路径 /api/v1/agent/**）。
 *
 * <h3>五个端点，一条接力链的完整生命周期</h3>
 * <pre>
 *   L15  POST /collaboration-sessions                        开局（链为空的特例，predecessor = null）
 *   L15  POST /collaboration-handoffs/claim                   接力入队（原子消费一根接力棒）
 *   L16  GET  /contribution-tickets/{code}                    查席位状态（CLI wait 轮询这里）
 *   L16  POST /contribution-tickets/{code}/leases             领租约（拿下这一棒的独占写作权）
 *   L16  POST /contribution-tickets/{code}/contributions      提交贡献（写进草稿 + 交棒给下一个）
 * </pre>
 *
 * <p>★ 这条链上依然<b>没有 publish</b>。ACPP 只负责排队与写作，
 * 发布权永远在主人的 {@code /owner/drafts/{id}/publish}（三道门的第二道门，硬安全线）。
 *
 * <p>★ 身份读 shared 的 {@link AgentIdentity} 接口，不 import identity 的实现类——
 * 守 collaboration→identity 的 Modulith 边界（依赖倒置）。
 *
 * <h3>幂等（L16 补齐了 L15 登记的 P3 欠账）</h3>
 * 四个写端点全部标注 {@link Idempotent}，由横切切面统一处理，业务代码里看不见幂等逻辑。
 * 采用<b>宽松模式</b>（{@code required=false}）：没带 Idempotency-Key 就当普通请求放行——
 * 这样 L15 那批既有 CLI 不会因为本课上线而突然全挂。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCollaborationController {

    /** 租约令牌请求头。活契约 components.securitySchemes.leaseToken 定义。 */
    private static final String LEASE_TOKEN_HEADER = "X-Turn-Lease-Token";

    private final StartCollaborationService startCollaborationService;
    private final ClaimHandoffService claimHandoffService;
    private final TicketStatusService ticketStatusService;
    private final ClaimLeaseService claimLeaseService;
    private final SubmitContributionService submitContributionService;
    private final ReportClientFailureService reportClientFailureService;
    private final PrecedingContentService precedingContentService;

    /** 前端基址：机娘拿不到浏览器地址，草稿审稿链接必须由服务端拼好给它（同 L14）。 */
    private final String webBaseUrl;

    public AgentCollaborationController(StartCollaborationService startCollaborationService,
                                       ClaimHandoffService claimHandoffService,
                                       TicketStatusService ticketStatusService,
                                       ClaimLeaseService claimLeaseService,
                                       SubmitContributionService submitContributionService,
                                       ReportClientFailureService reportClientFailureService,
                                       PrecedingContentService precedingContentService,
                                       @Value("${agentlog.web.base-url}") String webBaseUrl) {
        this.startCollaborationService = startCollaborationService;
        this.claimHandoffService = claimHandoffService;
        this.ticketStatusService = ticketStatusService;
        this.claimLeaseService = claimLeaseService;
        this.submitContributionService = submitContributionService;
        this.reportClientFailureService = reportClientFailureService;
        this.precedingContentService = precedingContentService;
        this.webBaseUrl = webBaseUrl;
    }

    /** 开局：建会话 + 首棒席位 + 第一根悬空尾令牌。201。 */
    @PostMapping("/collaboration-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    public StartCollaborationResponse start(
            @AuthenticationPrincipal AgentIdentity principal,
            @Valid @RequestBody StartCollaborationRequest request) {
        return startCollaborationService.start(principal, request);
    }

    /** 接力：原子消费一根接力棒 → 新席位 + 新尾令牌。201。 */
    @PostMapping("/collaboration-handoffs/claim")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    @RateLimit(scope = RateLimit.Scope.CLAIM_HANDOFF)
    public StartCollaborationResponse claimHandoff(
            @AuthenticationPrincipal AgentIdentity principal,
            @Valid @RequestBody ClaimHandoffRequest request) {
        return claimHandoffService.claim(principal, request);
    }

    /**
     * 查席位状态。CLI {@code collab wait} 轮询这里，按响应里的 pollAfterSeconds 决定睡多久。
     *
     * <p>★ 等待发生在客户端：服务端不做长轮询、不持长连接、不占线程。
     * 一个机娘可能要等十几分钟，把等待成本放在最便宜的一侧。
     */
    @GetMapping("/contribution-tickets/{ticketCode}")
    @RateLimit(scope = RateLimit.Scope.TICKET_STATUS)
    public TicketStatusView ticketStatus(
            @AuthenticationPrincipal AgentIdentity principal,
            @PathVariable String ticketCode) {
        return ticketStatusService.get(principal, ticketCode);
    }

    /** 领租约：拿下这一棒的独占写作权，返回只出现一次的 lease 令牌明文。201。 */
    @PostMapping("/contribution-tickets/{ticketCode}/leases")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    public ClaimLeaseResponse claimLease(
            @AuthenticationPrincipal AgentIdentity principal,
            @PathVariable String ticketCode) {
        return claimLeaseService.claim(principal, ticketCode);
    }

    /**
     * 提交贡献：正文写进草稿、席位落 DONE、唤醒下一棒。201。
     *
     * <p>租约令牌走请求头而不是请求体，令牌就不会出现在请求日志的 body 里（同 Bearer 的处置）。
     */
    @PostMapping("/contribution-tickets/{ticketCode}/contributions")
    @ResponseStatus(HttpStatus.CREATED)
    @Idempotent
    public SubmitContributionResponse submitContribution(
            @AuthenticationPrincipal AgentIdentity principal,
            @PathVariable String ticketCode,
            @RequestHeader(LEASE_TOKEN_HEADER) String leaseToken,
            @Valid @RequestBody SubmitContributionRequest request) {
        SubmitContributionService.SubmitOutcome outcome =
                submitContributionService.submit(principal, ticketCode, leaseToken, request);
        // 草稿审稿地址由这里拼（同 L14）：webBaseUrl 是表现层配置，Service 与 Facade 都不该知道它。
        String draftUrl = webBaseUrl + "/#/owner/drafts/" + outcome.draftId();
        return new SubmitContributionResponse(
                outcome.ticketCode(),
                outcome.ticketStatus(),
                draftUrl,
                // ★ 恒为 null（DRIFT D-16 第 2 条）：令牌明文绝不落库，submit 时拿不回尾令牌明文；
                //   而主人在 join 时早已拿到过它。安全铁律不为一个冗余字段让步。
                null);
    }

    /**
     * 写作前文（L18 补）：「我这一棒之前，这篇文章已经长成什么样」。
     *
     * <h3>★ 它补的是一个从 L15 就存在、到 L18 验收才被发现的缺口</h3>
     * 在这之前，机娘侧所有端点<b>没有一个能读到前面已完成棒次的正文</b>——
     * {@code claim lease} 的 {@code writingContext} 只告诉它「前面写了 N 棒」，
     * <b>不告诉它写了什么</b>。设计上靠主人手动复制粘贴：
     * 接力棒 51 个字符复制一次不痛，<b>正文几百上千字、每接一棒都要复制一次</b>。
     *
     * <p>★ 外部佐证：多智能体协作平台 Raft（raft.build）把「新人能从之前工作过的 agent
     * 那里拿到全部上下文」列为核心卖点——<b>上下文传递是这类产品的基本盘</b>。
     *
     * <p>返回的是 <b>{@code draft_block} 渲染层</b>而不是 {@code contribution} 原始层：
     * 续写要基于「文章<b>现在</b>是什么样」，主人润色过的地方必须让下一棒看到。
     *
     * <p>限流复用 {@code TICKET_STATUS}：它和查票一样是机娘会反复调的读端点。
     */
    @GetMapping("/contribution-tickets/{ticketCode}/preceding-content")
    @RateLimit(scope = RateLimit.Scope.TICKET_STATUS)
    public PrecedingContentView precedingContent(
            @AuthenticationPrincipal AgentIdentity principal,
            @PathVariable String ticketCode) {
        return precedingContentService.get(principal, ticketCode);
    }

    /**
     * 自报失败（L18）：机娘知道自己写不下去了，主动认输，<b>不用干等 15 分钟租约超时</b>。
     *
     * <h3>★ 它填上了一个「值集列了、代码从没产生过」的状态</h3>
     * {@code FAILED_CLIENT} 从 V013 起就在 CHECK 值集里（注释标着「L18」），
     * 但在本课之前<b>没有任何端点能产生它</b>。
     *
     * <h3>★ 单开这个端点的主要收益不是省那 15 分钟，而是把「症状」换成「原因」</h3>
     * 超时那条路径只能写「租约超时，未能在有效期内提交」——服务端<b>根本不知道为什么</b>
     * （对话崩了？需求不清？工具报错？）。自报失败带着机娘自己说的理由，
     * 对主人而言「上一棒内容缺了关键信息」比「租约超时」有用一百倍：
     * 前者他能立刻决定怎么办，后者他只能猜。
     *
     * <p>租约令牌走请求头（同 submit）：令牌不进请求日志的 body。
     * 返回最新席位状态，让机娘一眼看出「这一棒已作废，接下来等主人」。
     */
    @PostMapping("/contribution-tickets/{ticketCode}/failure")
    @Idempotent
    public TicketStatusView reportFailure(
            @AuthenticationPrincipal AgentIdentity principal,
            @PathVariable String ticketCode,
            @RequestHeader(LEASE_TOKEN_HEADER) String leaseToken,
            @Valid @RequestBody ReportFailureRequest request) {
        return reportClientFailureService.reportFailure(
                principal, ticketCode, leaseToken, request.reason());
    }
}
