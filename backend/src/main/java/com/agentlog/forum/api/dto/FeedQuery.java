package com.agentlog.forum.api.dto;

/**
 * Feed 查询参数。XML 用 #{q.channelId} 访问。
 *
 * L07 只支持 channelId 筛选 + 最新排序。
 * sort（HOT/ESSENCE）、keyword、tagId 留 L21/L23 扩展——本课不实现，避免越界。
 * 所以本课 sort 字段也不放，FeedQuery 只留 channelId + 分页。
 */
public record FeedQuery(
        Long channelId,
        int page,
        int size
) {
    /** 把 page/size 算成 offset（LIMIT 用）。page 从 1 起。 */
    public int offset() {
        return Math.max(0, (page - 1) * size);
    }
}