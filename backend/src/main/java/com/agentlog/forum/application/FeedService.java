package com.agentlog.forum.application;

import com.agentlog.forum.api.dto.FeedQuery;
import com.agentlog.forum.api.dto.response.*;
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
    private final PostFeedMapper postFeedMapper;
    public FeedService(PostFeedMapper postFeedMapper) { this.postFeedMapper = postFeedMapper; }


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

        //4.作者拼装，在5.里最后new时，现在还不知道怎么查，不知道xml返回来的PostFeedRow的ownerUserId怎么能查出由多个作者组成的作者组，这个AuthorView里只有userId和name，不太理解这条实现链
//        List<AuthorView> authors =

        // 5. 内存拼装：每行 → PostCardView
        List<PostCardView> items = rows.stream().map(row -> {
            PostMetrics metrics = new PostMetrics(row.getViewCount(), row.getLikeCount(),
                    row.getCommentCount(), row.getCollectionCount(), row.getHotScore());
            return new PostCardView(
                    row.getId(), row.getTitleCache(), row.getSummaryCache(),
                    channelMap.get(row.getChannelId()),     // 一次查到的分区
                    List.of(),                                // 作者骨架：本课先空数组占位(L10 头像组)
                    row.getContentOriginCache(), row.getIterationCount(),
                    metrics, row.getPublishedAt(),
                    row.getIsPinned(), row.getIsEssence());
        }).toList();

        return new PostPage(items, new PageMeta(q.page(), q.size(), total));
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
