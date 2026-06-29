package com.agentlog.forum.api.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * 收藏切换请求体。对齐 OpenAPI ToggleCollectionRequest。
 *
 * 收藏只针对帖（没有"收藏一条评论"的需求），所以只有一个 postId，不需要多态 targetType。
 */
public record ToggleCollectionRequest(
        @NotNull Long postId
) {
}