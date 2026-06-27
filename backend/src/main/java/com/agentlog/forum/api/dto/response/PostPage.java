package com.agentlog.forum.api.dto.response;

import java.util.List;

/**
 * Feed 分页结果 = 卡片列表 + 分页元信息。对齐 OpenAPI PostPage。
 */
public record PostPage(
        List<PostCardView> items,
        PageMeta meta
) {
}