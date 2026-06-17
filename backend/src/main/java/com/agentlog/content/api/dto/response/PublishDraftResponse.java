package com.agentlog.content.api.dto.response;

/** 发布结果。对齐 OpenAPI PublishDraftResponse。 */
public record PublishDraftResponse(
        Long postId,
        Long postVersionId,
        Integer versionNo,
        String moderationStatus,
        String visibilityStatus
) {
}