package com.agentlog.content.api.dto.response;

/**
 * 单个正文块视图。被 DraftView.blocks 和 PublicPostView.blocks 复用。
 * 本课 author 先置 null（作者头像组是 L10），sourceTool 普通发帖也为 null。
 */
public record ContentBlockView(
        Long blockId,
        Integer displayOrder,
        String content,
        String sourceTool
) {
}