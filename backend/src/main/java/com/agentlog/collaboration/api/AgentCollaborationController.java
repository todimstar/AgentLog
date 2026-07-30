package com.agentlog.collaboration.api;

import com.agentlog.collaboration.api.dto.request.ClaimHandoffRequest;
import com.agentlog.collaboration.api.dto.request.StartCollaborationRequest;
import com.agentlog.collaboration.api.dto.response.StartCollaborationResponse;
import com.agentlog.collaboration.application.ClaimHandoffService;
import com.agentlog.collaboration.application.StartCollaborationService;
import com.agentlog.shared.security.AgentIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACPP 接力入口（L15 · Chain 3 保护，路径 /api/v1/agent/**）。
 *
 * 两个端点，一件事的两个入口——「往因果链尾部追加一个席位，并签发新的悬空尾令牌」：
 *   POST /collaboration-sessions        开局（链为空的特例，predecessor = null）
 *   POST /collaboration-handoffs/claim  接力（链非空的通例，原子消费一根棒子）
 * 故两者共用 StartCollaborationResponse——协议的对称性，客户端一套代码处理两种入口。
 *
 * ★ 这条链上依然【没有 publish】。ACPP 只负责排队与写作，
 *   发布权永远在主人的 /owner/drafts/{id}/publish（三道门的第二道门，硬安全线）。
 *
 * ★ 身份读 shared 的 AgentIdentity 接口，不 import identity 的 AgentPrincipal 实现类——
 *   守 collaboration→identity 的 Modulith 边界（依赖倒置，同 L14 的做法，第二次复用）。
 *
 * ⚠️ 幂等（Idempotency-Key）本课【不实装】（主人 2026-07-30 拍板，登记 DRIFT D-15）：
 *   契约两端点都声明了 idempotencyKey，但 idempotency_record 表排在 L16。
 *   claim 侧靠原子消费天然只成功一次（重放必得 409，语义已正确）；
 *   start 侧重放的唯一代价是多一条空 session（无内容、可忽略）。诚实登记为已知缺口，L16 补齐。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentCollaborationController {

    private final StartCollaborationService startCollaborationService;
    private final ClaimHandoffService claimHandoffService;

    public AgentCollaborationController(StartCollaborationService startCollaborationService,
                                       ClaimHandoffService claimHandoffService) {
        this.startCollaborationService = startCollaborationService;
        this.claimHandoffService = claimHandoffService;
    }

    /** 开局：建会话 + 首棒席位 + 第一根悬空尾令牌。201。 */
    @PostMapping("/collaboration-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public StartCollaborationResponse start(
            @AuthenticationPrincipal AgentIdentity principal,
            @Valid @RequestBody StartCollaborationRequest request) {
        return startCollaborationService.start(principal, request);
    }

    /** 接力：原子消费一根接力棒 → 新席位 + 新尾令牌。201。 */
    @PostMapping("/collaboration-handoffs/claim")
    @ResponseStatus(HttpStatus.CREATED)
    public StartCollaborationResponse claimHandoff(
            @AuthenticationPrincipal AgentIdentity principal,
            @Valid @RequestBody ClaimHandoffRequest request) {
        return claimHandoffService.claim(principal, request);
    }
}
