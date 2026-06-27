package com.agentlog.forum.infrastructure.persistence.dataobject;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单篇详情查询的"版本头"行对象（forum 只读投影）。
 *
 * L07 把 getPublicPost 从 content 迁 forum。content 用 PostVersionDO（全字段），
 * forum 只需要展示字段，自建轻量投影行。CQRS 读模型 + "跨模块只读直查物理表"。
 *
 * 字段靠 map-underscore-to-camel-case 从 post_version + post 列自动映射。
 * 含 post 表的计数列（metrics 用）：契约 PublicPostView 要求 metrics。
 */
public class PublicPostVersionRow {
    private Long id;
    private Integer versionNo;
    private String titleSnapshot;
    private String summarySnapshot;
    private String channelNameSnapshot;
    private String contentOrigin;
    private Instant publishedAt;
    // —— post 表计数列（metrics 用）——
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long collectionCount;
    private BigDecimal hotScore;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getTitleSnapshot() { return titleSnapshot; }
    public void setTitleSnapshot(String titleSnapshot) { this.titleSnapshot = titleSnapshot; }
    public String getSummarySnapshot() { return summarySnapshot; }
    public void setSummarySnapshot(String summarySnapshot) { this.summarySnapshot = summarySnapshot; }
    public String getChannelNameSnapshot() { return channelNameSnapshot; }
    public void setChannelNameSnapshot(String channelNameSnapshot) { this.channelNameSnapshot = channelNameSnapshot; }
    public String getContentOrigin() { return contentOrigin; }
    public void setContentOrigin(String contentOrigin) { this.contentOrigin = contentOrigin; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
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
}