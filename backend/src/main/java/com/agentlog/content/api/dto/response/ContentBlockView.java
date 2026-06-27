package com.agentlog.content.api.dto.response;

/**
 * 单个正文块视图（content 模块版，草稿详情 DraftView 用）。
 *
 * forum 模块也有一份同名 ContentBlockView（发布帖详情用）——两模块各持一份，
 * 不互相 import（Modulith 边界）。这和 ChannelView 同理：读模型各自独立。
 */
public record ContentBlockView(
        Long blockId,
        Integer displayOrder,
        String content,
        String sourceTool
) {
}