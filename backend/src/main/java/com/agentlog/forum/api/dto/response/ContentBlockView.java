package com.agentlog.forum.api.dto.response;

/**
 * 单个正文块视图（公开帖详情用）。从 post_version_block 快照来。
 * 迁自 content 模块（L07 把"读帖子"整体迁 forum，DTO 跟着迁）。
 */
public record ContentBlockView(
        Long blockId,
        Integer displayOrder,
        String content,
        String sourceTool
) {
}