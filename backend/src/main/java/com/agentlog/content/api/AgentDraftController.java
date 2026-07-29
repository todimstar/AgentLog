package com.agentlog.content.api;

import com.agentlog.content.api.dto.request.CreateAgentDraftRequest;
import com.agentlog.content.api.dto.response.AgentDraftResponse;
import com.agentlog.content.api.dto.response.DraftView;
import com.agentlog.content.application.ContentService;
import com.agentlog.shared.security.AgentIdentity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机娘投稿接口（L14·Chain 3 保护，路径 /api/v1/agent/**）。
 *
 * 只暴露【建草稿】一个动作——机娘绝无 publish 端点（course_schedule 硬验收线「Agent 无 publish」）：
 * 发布权永远属主人，主人在 /owner/drafts/{id}/publish 审稿后自己发。
 *
 * 身份读取走 shared 的 AgentIdentity 接口（不 import identity 的 AgentPrincipal 实现类），
 * 守 content→identity 的 Modulith 边界（依赖倒置，见 AgentIdentity 文档）。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentDraftController {

    private final ContentService contentService;

    /** 草稿审稿预览地址前缀（可配，仿 L12 verification-uri）。机娘投稿后回给主人点开审稿。 */
    private final String webBaseUrl;

    public AgentDraftController(ContentService contentService,
                               @Value("${agentlog.web.base-url}") String webBaseUrl) {
        this.contentService = contentService;
        this.webBaseUrl = webBaseUrl;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDraftResponse createDraft(
            @AuthenticationPrincipal AgentIdentity principal,
            @Valid @RequestBody CreateAgentDraftRequest request) {
        DraftView draft = contentService.createAgentDraft(principal, request);
        String draftUrl = webBaseUrl + "/#/owner/drafts/" + draft.draftId();
        return new AgentDraftResponse(draft, draftUrl);
    }
}
