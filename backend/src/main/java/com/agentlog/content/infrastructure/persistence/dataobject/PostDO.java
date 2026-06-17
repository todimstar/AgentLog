package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * post 表的 DO —— "逻辑帖子壳"。对应 V005__create_post_content_core.sql 的 post 表。
 *
 * 它不存正文！正文在 draft_block（草稿期）和 post_version_block（发布后）。
 * post 这一行只挂：归属(owner/channel)、当前发布版本指针、可见性、卡片缓存字段、各种计数。
 * 这正是 L06 "禁止 post.content" 的体现——壳与正文分离。
 *
 * 本课只会用到极少数字段（owner_user_id/channel_id/visibility_status/current_published_version_id），
 * 其余 title_cache/各 count/hot_score 等是 L07+ Feed 卡片和 L09/L23 计数才填，现在保持默认值。
 *
 * 手写 getter/setter（不用 Lombok）：学习项目里保持代码透明、可读可控；
 * DO 必须是可变 class（MyBatis-Plus 靠无参构造 + setter 从数据库回填字段），不能用 record。
 */
@TableName("post")
public class PostDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;                 // 帖子归属哪个用户（租户隔离键，从认证 principal 派生）
    private Long channelId;                    // 所属分区
    private Long currentPublishedVersionId;    // 指针：当前线上读哪个 post_version；未发布时为 null
    private String visibilityStatus;           // DRAFT_ONLY / PUBLISHED / HIDDEN_* / DELETED

    // —— 卡片缓存字段：Feed 列表不扫正文，直接读这里（L07 起填充）——
    private String titleCache;
    private String summaryCache;
    private String coverMediaPublicId;
    private String contentOriginCache;         // HUMAN_ONLY / AI_ASSISTED / AI_GENERATED

    private Integer iterationCount;

    // —— 计数冗余字段：避免实时 COUNT(*)（L09/L23）——
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long collectionCount;
    private BigDecimal hotScore;               // 热门分数（DECIMAL(20,6) ←→ BigDecimal）

    private Boolean isPinned;
    private Boolean isEssence;
    private Instant publishedAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    public Long getCurrentPublishedVersionId() {
        return currentPublishedVersionId;
    }

    public void setCurrentPublishedVersionId(Long currentPublishedVersionId) {
        this.currentPublishedVersionId = currentPublishedVersionId;
    }

    public String getVisibilityStatus() {
        return visibilityStatus;
    }

    public void setVisibilityStatus(String visibilityStatus) {
        this.visibilityStatus = visibilityStatus;
    }

    public String getTitleCache() {
        return titleCache;
    }

    public void setTitleCache(String titleCache) {
        this.titleCache = titleCache;
    }

    public String getSummaryCache() {
        return summaryCache;
    }

    public void setSummaryCache(String summaryCache) {
        this.summaryCache = summaryCache;
    }

    public String getCoverMediaPublicId() {
        return coverMediaPublicId;
    }

    public void setCoverMediaPublicId(String coverMediaPublicId) {
        this.coverMediaPublicId = coverMediaPublicId;
    }

    public String getContentOriginCache() {
        return contentOriginCache;
    }

    public void setContentOriginCache(String contentOriginCache) {
        this.contentOriginCache = contentOriginCache;
    }

    public Integer getIterationCount() {
        return iterationCount;
    }

    public void setIterationCount(Integer iterationCount) {
        this.iterationCount = iterationCount;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public Long getLikeCount() {
        return likeCount;
    }

    public void setLikeCount(Long likeCount) {
        this.likeCount = likeCount;
    }

    public Long getCommentCount() {
        return commentCount;
    }

    public void setCommentCount(Long commentCount) {
        this.commentCount = commentCount;
    }

    public Long getCollectionCount() {
        return collectionCount;
    }

    public void setCollectionCount(Long collectionCount) {
        this.collectionCount = collectionCount;
    }

    public BigDecimal getHotScore() {
        return hotScore;
    }

    public void setHotScore(BigDecimal hotScore) {
        this.hotScore = hotScore;
    }

    public Boolean getIsPinned() {
        return isPinned;
    }

    public void setIsPinned(Boolean isPinned) {
        this.isPinned = isPinned;
    }

    public Boolean getIsEssence() {
        return isEssence;
    }

    public void setIsEssence(Boolean isEssence) {
        this.isEssence = isEssence;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
