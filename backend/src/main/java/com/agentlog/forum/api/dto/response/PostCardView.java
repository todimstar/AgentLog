package com.agentlog.forum.api.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Feed 卡片视图（一屏列表里的一张卡）。
 *
 * ⚠️ 关键：字段全来自 post 表的【冗余缓存列】，不扫正文表！
 *   title_cache/summary_cache/各 count/hot_score/published_at —— 这些是 L06 发布(TX-02)时回填的。
 *   正是 L07 验收③"无逐篇正文请求"的体现：Feed 只读 post 一行，不 join post_version_block 正文。
 *   （若卡片字段去正文表取，一页 20 篇就 20 次正文查询，N+1 的另一面。）
 *
 * contentOrigin/pinned/essence 本课 L07 Feed 列表先展示用；HOT/精华排序 L23 才强化。
 * authors：L10 批量补 OWNER 作者头像组。
 */
public record PostCardView(
        Long postId,
        String title,
        String summary,
        ChannelView channel,
        List<AuthorView> authors,
        String contentOrigin,
        Integer iterationCount,
        PostMetrics metrics,
        Instant publishedAt,
        Boolean pinned,
        Boolean essence
) {
}
