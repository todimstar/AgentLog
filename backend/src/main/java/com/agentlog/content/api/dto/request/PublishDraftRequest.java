package com.agentlog.content.api.dto.request;

/**
 * 草稿到发布DTO
 * 发布的草稿版本
 * 发布方式 publishMode
 */

public record PublishDraftRequest(Long expectedDraftVersion, String publishMode) {
}