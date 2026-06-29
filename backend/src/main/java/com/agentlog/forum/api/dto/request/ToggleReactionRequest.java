package com.agentlog.forum.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 点赞切换请求体。对齐 OpenAPI ToggleReactionRequest。
 *
 * targetType 多态指：POST（赞帖）/ COMMENT（赞评论），统一 reaction 表靠它区分指向。
 * targetId 是被赞目标的 id（post.id 或 comment.id，依 targetType 而定）。
 * 用一张表存两种对象的赞，是 reaction 表的多态设计。
 */
public record ToggleReactionRequest(
        @NotBlank String targetType,    // POST / COMMENT
        @NotNull Long targetId          // 目标 id
) {
}