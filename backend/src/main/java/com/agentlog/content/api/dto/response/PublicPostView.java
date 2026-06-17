package com.agentlog.content.api.dto.response;

import com.agentlog.content.infrastructure.persistence.dataobject.PostVersionBlockDO;
import com.agentlog.content.infrastructure.persistence.dataobject.PostVersionDO;

import java.time.Instant;
import java.util.List;

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
    public static PublicPostView form(Long postId, PostVersionDO version, List<PostVersionBlockDO> blockDOs){
        List<ContentBlockView> blocks = blockDOs.stream()
                .map(b -> new ContentBlockView(
                        b.getId(),
                        b.getDisplayOrder(),
                        b.getContentSnapshot(),   // ← 读快照字段，不是 renderedContent
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
                blocks
        );
    }
}
