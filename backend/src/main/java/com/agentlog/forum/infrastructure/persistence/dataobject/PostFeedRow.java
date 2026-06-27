package com.agentlog.forum.infrastructure.persistence.dataobject;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Feed 查询的内部行对象（PostFeedRow）—— forum 模块的【读模型】。
 *
 * ⚠️ 这不是给前端的 DTO，是 XML 查询的临时承载对象。为什么需要它而不是直接 resultType=PostCardView？
 *   - 它含 owner_user_id 和 channel_id：Service 要用这俩去批量查作者、查分区（N+1 防范）。
 *   - PostCardView（前端 DTO）不该暴露 owner_user_id，且它的 channel 是 ChannelView 对象、authors 是列表——
 *     XML 一次 SELECT 拿不到（要 join 才行，而 join 正文/作者就是 N+1 红线）。
 *   - 所以流程是：XML 查出一行行 PostFeedRow（扁平行）→ Service 批量补 channel/authors → 转成 PostCardView。
 *
 * 这是 CQRS 读模型的体现：content 用 PostDO（全字段+乐观锁，给写），forum 用 PostFeedRow（投影，给读），
 * 同一张 post 物理表，两个模型。module-contracts 说 ForumFacade.listFeed 归 forum，正是此意。
 *
 * 字段靠 map-underscore-to-camel-case 自动从 post 列映射（title_cache→titleCache 等）。
 */
public class PostFeedRow {
    private Long id;
    private String titleCache;
    private String summaryCache;
    private String coverMediaPublicId;
    private String contentOriginCache;
    private Long channelId;
    private Long ownerUserId;   // 内部用：批量查作者
    private Integer iterationCount;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long collectionCount;
    private BigDecimal hotScore;
    private Boolean isPinned;
    private Boolean isEssence;
    private Instant publishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitleCache() { return titleCache; }
    public void setTitleCache(String titleCache) { this.titleCache = titleCache; }
    public String getSummaryCache() { return summaryCache; }
    public void setSummaryCache(String summaryCache) { this.summaryCache = summaryCache; }
    public String getCoverMediaPublicId() { return coverMediaPublicId; }
    public void setCoverMediaPublicId(String coverMediaPublicId) { this.coverMediaPublicId = coverMediaPublicId; }
    public String getContentOriginCache() { return contentOriginCache; }
    public void setContentOriginCache(String contentOriginCache) { this.contentOriginCache = contentOriginCache; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Integer getIterationCount() { return iterationCount; }
    public void setIterationCount(Integer iterationCount) { this.iterationCount = iterationCount; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public Long getLikeCount() { return likeCount; }
    public void setLikeCount(Long likeCount) { this.likeCount = likeCount; }
    public Long getCommentCount() { return commentCount; }
    public void setCommentCount(Long commentCount) { this.commentCount = commentCount; }
    public Long getCollectionCount() { return collectionCount; }
    public void setCollectionCount(Long collectionCount) { this.collectionCount = collectionCount; }
    public BigDecimal getHotScore() { return hotScore; }
    public void setHotScore(BigDecimal hotScore) { this.hotScore = hotScore; }
    public Boolean getIsPinned() { return isPinned; }
    public void setIsPinned(Boolean isPinned) { this.isPinned = isPinned; }
    public Boolean getIsEssence() { return isEssence; }
    public void setIsEssence(Boolean isEssence) { this.isEssence = isEssence; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
}