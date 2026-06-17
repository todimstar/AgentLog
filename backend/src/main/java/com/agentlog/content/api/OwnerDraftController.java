package com.agentlog.content.api;

import com.agentlog.content.api.dto.request.CreateOwnerDraftRequest;
import com.agentlog.content.api.dto.response.DraftView;
import com.agentlog.content.api.dto.request.PublishDraftRequest;
import com.agentlog.content.api.dto.response.PublishDraftResponse;
import com.agentlog.content.application.ContentService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 主人草稿接口。路径对齐 OpenAPI /api/v1/owner/drafts。
 * Controller 只做 HTTP 编排：拿登录身份 → 调 Service → 返回视图。
 */
@RestController
@RequestMapping("/api/v1/owner")
public class OwnerDraftController {

    private final ContentService contentService;

    public OwnerDraftController(ContentService contentService){
        this.contentService = contentService;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftView createDraft(@Valid @RequestBody CreateOwnerDraftRequest request){
        long currentUserId = CurrentUser.requireId();
        return contentService.createOwnerDraft(currentUserId,request);
    }

    @GetMapping("/drafts/{draftId}")
    public DraftView getDraft(@PathVariable long draftId){
        long currentUserId = CurrentUser.requireId();
        return contentService.getDraft(currentUserId,draftId);
    }

    @PostMapping("/drafts/{draftId}/publish")
    public PublishDraftResponse publish(
            @PathVariable long draftId,
            @Valid @RequestBody PublishDraftRequest request)
    {
        long currentUserId = CurrentUser.requireId();
        return contentService.publish(currentUserId,draftId,request);
    }

}
