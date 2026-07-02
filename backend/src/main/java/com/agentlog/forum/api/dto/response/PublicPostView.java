package com.agentlog.forum.api.dto.response;

import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostBlockRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostVersionRow;

import java.time.Instant;
import java.util.List;

/**
 * 读者看到的已发布帖详情。正文来自 post_version_block 快照（非草稿）。
 * 字段对齐 OpenAPI 契约 PublicPostView：postId/versionNo/title/summary/authors/blocks/
 * contentOrigin/channelName/publishedAt/metrics（attachments 媒体快照留 L11）。
 *
 * authors 详情页暂保留骨架；L10 先补 Feed 卡片作者头像组。
 * metrics 从 post 表的冗余计数缓存列读（同 Feed 卡片）。
 */
public record PublicPostView(
        Long postId,
        Integer versionNo,
        String title,
        String summary,
        List<AuthorView> authors,
        List<ContentBlockView> blocks,
        String contentOrigin,
        String channelName,
        Instant publishedAt,
        PostMetrics metrics
) {
    public static PublicPostView from(Long postId, PublicPostVersionRow version, List<PublicPostBlockRow> blockRows) {
        List<ContentBlockView> blocks = blockRows.stream()
                .map(b -> new ContentBlockView(
                        b.getId(),
                        b.getDisplayOrder(),
                        b.getContentSnapshot(),   // 读快照字段
                        b.getSourceTool()))
                .toList();
        PostMetrics metrics = new PostMetrics(
                version.getViewCount(),
                version.getLikeCount(),
                version.getCommentCount(),
                version.getCollectionCount(),
                version.getHotScore());
        return new PublicPostView(
                postId,
                version.getVersionNo(),
                version.getTitleSnapshot(),
                version.getSummarySnapshot(),
                List.of(),                        // authors 详情页骨架：Feed 卡片作者已在 L10 补齐
                blocks,
                version.getContentOrigin(),
                version.getChannelNameSnapshot(),
                version.getPublishedAt(),
                metrics);
    }
}
