package com.agentlog.content.api.dto.response;

import com.agentlog.content.infrastructure.persistence.dataobject.ForumChannelDO;

/** 分区视图(公开列表)。字段对齐 OpenAPI ChannelView。 */
public record ChannelView(
        Long id,
        String slug,
        String name,
        String description,
        Long postCount
) {
    public static ChannelView from(ForumChannelDO channel) {
        return new ChannelView(
                channel.getId(),
                channel.getSlug(),
                channel.getName(),
                channel.getDescription(),
                channel.getPostCount());
    }
}
