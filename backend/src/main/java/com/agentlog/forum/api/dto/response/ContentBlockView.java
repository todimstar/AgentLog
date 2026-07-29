package com.agentlog.forum.api.dto.response;

/**
 * 单个正文块视图（公开帖详情用）。从 post_version_block 快照来。
 * 迁自 content 模块（L07 把"读帖子"整体迁 forum，DTO 跟着迁）。
 *
 * author（契约 ContentBlockView.author）：这一块是谁写的——主人还是某个机娘。
 * 契约早就声明了这个字段，但后端一直没填（块只回 sourceTool），
 * 于是读者看不出"哪几段是机娘写的"。L14 机娘投稿落地后这个缺口才真正咬人，故独立补齐。
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
