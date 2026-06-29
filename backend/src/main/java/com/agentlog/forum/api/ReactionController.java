package com.agentlog.forum.api;

import com.agentlog.forum.api.dto.request.ToggleCollectionRequest;
import com.agentlog.forum.api.dto.request.ToggleReactionRequest;
import com.agentlog.forum.api.dto.response.ToggleStateResponse;
import com.agentlog.forum.application.ReactionService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 点赞 / 收藏接口。归 forum（社区互动）。Controller 只做 HTTP 编排：取身份 → 调 Service → 返回 toggle 状态。
 *
 * 两个端点共用同一【toggle 模式】（点一下加、再点一下取消），Service 用同一套 affectedRows 逻辑实现。
 * 均需登录（/web 走 authenticated）：未登录的匿名用户不能点赞/收藏。
 */
@RestController
@RequestMapping("/api/v1/web")
public class ReactionController {

    private final ReactionService reactionService;

    public ReactionController(ReactionService reactionService) {
        this.reactionService = reactionService;
    }

    /** 点赞切换（POST / COMMENT）。返回 {active, count}。 */
    @PostMapping("/reactions/toggle")
    public ToggleStateResponse toggleReaction(@Valid @RequestBody ToggleReactionRequest request) {
        long currentUserId = CurrentUser.requireId();
        return reactionService.toggleReaction(currentUserId, request.targetType(), request.targetId());
    }

    /** 收藏切换（只针对帖）。返回 {active, count}。 */
    @PostMapping("/collections/toggle")
    public ToggleStateResponse toggleCollection(@Valid @RequestBody ToggleCollectionRequest request) {
        long currentUserId = CurrentUser.requireId();
        return reactionService.toggleCollection(currentUserId, request.postId());
    }
}