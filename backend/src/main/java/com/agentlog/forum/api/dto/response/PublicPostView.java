package com.agentlog.forum.api.dto.response;

import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostBlockRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostVersionRow;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读者看到的已发布帖详情。正文来自 post_version_block 快照（非草稿）。
 * 字段对齐 OpenAPI 契约 PublicPostView：postId/versionNo/title/summary/authors/blocks/
 * contentOrigin/channelName/publishedAt/metrics（attachments 媒体快照留 L11）。
 *
 * authors：本版本所有块作者去重后的列表（主人 + 参与的机娘），按块顺序首次出现排序。
 *   先前这里返回空列表骨架（"L10 先补 Feed 卡片"），导致详情页作者区一直是空的——已随
 *   ContentBlockView.author 契约缺口一并补齐。
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
    /**
     * @param authorIndex 作者查找表，键由 {@link #authorKey} 生成（"U:12" / "A:1"）。
     *                    Service 负责批量查好传进来，本方法只做内存拼装（防 N+1）。
     */
    public static PublicPostView from(Long postId, PublicPostVersionRow version,
                                      List<PublicPostBlockRow> blockRows,
                                      Map<String, AuthorView> authorIndex) {
        // 去重容器：LinkedHashMap 保留"按块顺序首次出现"的次序，让作者列表读起来跟正文一致。
        Map<String, AuthorView> distinctAuthors = new LinkedHashMap<>();

        List<ContentBlockView> blocks = blockRows.stream()
                .map(b -> {
                    AuthorView author = resolveAuthor(b, authorIndex);
                    if (author != null) {
                        distinctAuthors.putIfAbsent(authorKey(b.getAuthorType(),
                                b.getAuthorUserId(), b.getAuthorAgentId()), author);
                    }
                    return new ContentBlockView(
                            b.getId(),
                            b.getDisplayOrder(),
                            b.getContentSnapshot(),   // 读快照字段
                            author,
                            b.getSourceTool());
                })
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
                List.copyOf(distinctAuthors.values()),
                blocks,
                version.getContentOrigin(),
                version.getChannelNameSnapshot(),
                version.getPublishedAt(),
                metrics);
    }

    /**
     * 块 → 作者。查不到（作者已删/历史数据缺字段）时退化成脱敏占位，而不是让 author 为 null——
     * 契约里 AuthorView.deleted 就是为这个场景准备的。作者维度整个为空的老数据才返回 null。
     */
    private static AuthorView resolveAuthor(PublicPostBlockRow b, Map<String, AuthorView> index) {
        String key = authorKey(b.getAuthorType(), b.getAuthorUserId(), b.getAuthorAgentId());
        if (key == null) {
            return null;    // L06 之前的历史块没有作者维度，如实留空
        }
        AuthorView found = index.get(key);
        if (found != null) {
            return found;
        }
        return b.getAuthorAgentId() != null
                ? AuthorView.deletedAgent(b.getAuthorAgentId())
                : AuthorView.deletedOwner(b.getAuthorUserId());
    }

    /** 作者查找键：人和机娘的 id 各自独立编号，必须带类型前缀区分，否则 userId=1 和 agentId=1 会撞。 */
    public static String authorKey(String authorType, Long authorUserId, Long authorAgentId) {
        if (authorAgentId != null) {
            return "A:" + authorAgentId;
        }
        if (authorUserId != null) {
            return "U:" + authorUserId;
        }
        return null;
    }
}
