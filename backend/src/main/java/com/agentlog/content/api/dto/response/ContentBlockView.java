package com.agentlog.content.api.dto.response;

/**
 * 单个正文块视图（content 模块版，草稿详情 DraftView 用）。
 *
 * forum 模块也有一份同名 ContentBlockView（发布帖详情用）——两模块各持一份，
 * 不互相 import（Modulith 边界）。这和 ChannelView 同理：读模型各自独立。
 *
 * author（契约 ContentBlockView.author）：这一块是谁写的——主人还是某个机娘。
 * 契约早就声明了这个字段，但后端一直没填，于是主人审稿时只看得到"来源工具 claude-code"、
 * 看不出是哪个机娘写的。L14 机娘投稿落地后这个缺口才真正咬人，故独立补齐。
 * 字段顺序对齐契约：blockId / displayOrder / content / author / sourceTool。
 */
public record ContentBlockView(
        Long blockId,
        Integer displayOrder,
        String content,
        AuthorView author,
        String sourceTool
) {
}
