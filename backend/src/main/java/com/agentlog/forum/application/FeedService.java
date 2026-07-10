package com.agentlog.forum.application;

import com.agentlog.forum.api.dto.FeedQuery;
import com.agentlog.forum.api.dto.response.*;
import com.agentlog.forum.infrastructure.persistence.dataobject.AuthorLookupRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PostFeedRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostBlockRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostVersionRow;
import com.agentlog.forum.infrastructure.persistence.mapper.PostFeedMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FeedService {

    /**
     * user_account.status 的 ACTIVE 值。跨模块只读投影直查约定：不 import identity 模块的
     * UserStatus 枚举（模块间零 Java 依赖），此字面量须与 identity 模块的 UserStatus.ACTIVE 对齐。
     */
    private static final String AUTHOR_STATUS_ACTIVE = "ACTIVE";

    private final PostFeedMapper postFeedMapper;
    public FeedService(PostFeedMapper postFeedMapper) { this.postFeedMapper = postFeedMapper; }


    /**
     * 要传回一页帖子
     * 先查帖子信息，在填充非post表中的信息，内存拼装避免n+1
     * @param q
     * @return
     */
    public PostPage listFeed(FeedQuery q) {
        //传入帖子offset，去mapper查对应分区对应offset的帖子们，再填充信息
        List<PostFeedRow> rows = postFeedMapper.selectFeed(q);

        long total = postFeedMapper.selectFeedCount(q);
        //要是查的为空的
        if(rows.isEmpty()) return new PostPage(List.of(),new PageMeta(q.page(), q.size(), total));

        // 3. 批量查分区（N+1 防范：收齐 channelIds 一次 IN，不逐卡查）
        Set<Long> channelIds = rows.stream().map(PostFeedRow::getChannelId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ChannelView> channelMap = channelIds.isEmpty() ? Map.of() :
                postFeedMapper.selectChannelsByIds(channelIds).stream()
                        .collect(Collectors.toMap(ChannelView::id, c -> c));

        // 4. 批量查作者：PostFeedRow 只带 ownerUserId，Service 再把 id 批量翻译成 AuthorView。
        Set<Long> ownerIds = rows.stream().map(PostFeedRow::getOwnerUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());//过滤空和去重id
        Map<Long, AuthorView> authorMap = ownerIds.isEmpty() ? Map.of() :
                postFeedMapper.selectAuthorsByUserIds(ownerIds).stream()
                        .collect(Collectors.toMap(AuthorLookupRow::getUserId, this::toAuthorView));

        // 5. 内存拼装：每行 → PostCardView
        List<PostCardView> items = rows.stream().map(row -> {
            PostMetrics metrics = new PostMetrics(row.getViewCount(), row.getLikeCount(),
                    row.getCommentCount(), row.getCollectionCount(), row.getHotScore());
            AuthorView author = authorMap.getOrDefault(row.getOwnerUserId(), AuthorView.deletedOwner(row.getOwnerUserId()));
            return new PostCardView(
                    row.getId(), row.getTitleCache(), row.getSummaryCache(),
                    channelMap.get(row.getChannelId()),     // 一次查到的分区
                    List.of(author),                          // L10：OWNER 作者头像组
                    row.getContentOriginCache(), row.getIterationCount(),
                    metrics, row.getPublishedAt(),
                    row.getIsPinned(), row.getIsEssence());
        }).toList();

        return new PostPage(items, new PageMeta(q.page(), q.size(), total));
    }

    private AuthorView toAuthorView(AuthorLookupRow row) {
        if (!AUTHOR_STATUS_ACTIVE.equals(row.getStatus())) {
            return AuthorView.deletedOwner(row.getUserId());
        }
        return AuthorView.owner(row.getUserId(), row.getUsername(), row.getAvatarMediaId());
    }

    /**
     * 单篇详情（L07 从 content 迁来）。顺 post.current_published_version_id 指针读发布快照。
     * 只认 PUBLISHED 帖——草稿/未发布对读者就是"不存在"（POST_NOT_FOUND）。
     * 跨模块只读：forum 用自己的 XML 直查 post/post_version/post_version_block 物理表，零依赖 content。
     */
    public PublicPostView getPublicPost(long postId) {
        PublicPostVersionRow version = postFeedMapper.selectPublishedVersionByPostId(postId);
        if (version == null) {
            throw new ApiException(ApiStatus.POST_NOT_FOUND);
        }
        List<PublicPostBlockRow> blocks = postFeedMapper.selectVersionBlocks(version.getId());
        return PublicPostView.from(postId, version, blocks);
    }

    /** 公开分区列表（L07 从 content 迁来）。发帖选分区 + Feed 筛选用。只列启用、按 sort_order。 */
    public List<ChannelView> listChannels() {
        return postFeedMapper.selectEnabledChannels();
    }
}
