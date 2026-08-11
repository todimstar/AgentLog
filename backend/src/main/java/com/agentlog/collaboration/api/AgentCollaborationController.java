package com.agentlog.collaboration.api;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.request.SubmitContributionRequest;
import com.agentlog.collaboration.api.dto.response.ClaimLeaseResponse;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.api.dto.response.SubmitContributionResponse;
import com.agentlog.collaboration.api.dto.response.TicketStatusView;
import com.agentlog.collaboration.application.ClaimHandoffService;
import com.agentlog.collaboration.application.ClaimLeaseService;
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

    /** 前端基址：机娘拿不到浏览器地址，草稿审稿链接必须由服务端拼好给它（同 L14）。 */
    private final String webBaseUrl;

    public AgentCollaborationController(StartCollaborationService startCollaborationService,
                                       ClaimHandoffService claimHandoffService,
                                       TicketStatusService ticketStatusService,
                                       ClaimLeaseService claimLeaseService,
                                       SubmitContributionService submitContributionService,
                                       @Value("${agentlog.web.base-url}") String webBaseUrl) {
        this.startCollaborationService = startCollaborationService;
        this.claimHandoffService = claimHandoffService;
        this.ticketStatusService = ticketStatusService;
        this.claimLeaseService = claimLeaseService;
        this.submitContributionService = submitContributionService;
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
}
