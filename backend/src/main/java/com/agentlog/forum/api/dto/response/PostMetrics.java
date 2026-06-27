package com.agentlog.forum.api.dto.response;

/**
 * 帖子计数指标（Feed 卡片底部的「浏览/赞/评/藏」数字）。
 * 字段对应 post 表的冗余计数缓存列（L06 建表时埋好）。
 */
public record PostMetrics(
        Long viewCount,
        Long likeCount,
        Long commentCount,
        Long collectionCount,
        java.math.BigDecimal hotScore
) {
}