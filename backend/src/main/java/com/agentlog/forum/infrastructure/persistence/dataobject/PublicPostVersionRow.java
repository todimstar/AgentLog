package com.agentlog.forum.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 单篇详情查询的"版本头"行对象（forum 只读投影）。
 *
 * L07 把 getPublicPost 从 content 迁 forum。content 用 PostVersionDO（全字段），
 * forum 只需要展示字段（标题/摘要/分区名/起源/版本号/发布时间），自建轻量投影行。
 * 这是 CQRS 读模型 + "跨模块只读直查物理表"原则的体现（不依赖 content 的 DO）。
 *
 * 字段靠 map-underscore-to-camel-case 从 post_version 列自动映射。
 */
public class PublicPostVersionRow {
    private Long id;
    private Integer versionNo;
    private String titleSnapshot;
    private String summarySnapshot;
    private String channelNameSnapshot;
    private String contentOrigin;
    private Instant publishedAt;

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
}