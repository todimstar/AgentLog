package com.agentlog.forum.api.dto.response;

import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostBlockRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostVersionRow;

import java.time.Instant;
import java.util.List;

/**
 * 读者看到的已发布帖详情。正文来自 post_version_block 快照（非草稿）。
 * 迁自 content 模块（L07 把"读帖子"整体迁 forum）。
 *
 * from 入参用 forum 自己的只读投影行（PublicPostVersionRow / PublicPostBlockRow），
 * 不依赖 content 的 PostVersionDO——跨模块只读直查物理表的体现。
 */
public record PublicPostView(
        Long postId,
        String title,
        String summary,
        String channelName,
        String contentOrigin,
        Integer versionNo,
        Instant publishedAt,
        List<ContentBlockView> blocks
) {
    public static PublicPostView from(Long postId, PublicPostVersionRow version, List<PublicPostBlockRow> blockRows) {
        List<ContentBlockView> blocks = blockRows.stream()
                .map(b -> new ContentBlockView(
                        b.getId(),
                        b.getDisplayOrder(),
                        b.getContentSnapshot(),   // 读快照字段
                        b.getSourceTool()))
                .toList();
        return new PublicPostView(
                postId,
                version.getTitleSnapshot(),
                version.getSummarySnapshot(),
                version.getChannelNameSnapshot(),
                version.getContentOrigin(),
                version.getVersionNo(),
                version.getPublishedAt(),
                blocks);
    }
}