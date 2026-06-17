package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * post_version 表的 DO —— 📸发布快照（版本头）。对应 V005 的 post_version 表。
 *
 * 每次主人"批准发布"就新增一行（version_no 累加：v1、v2、v3…）。
 * 它是【不可变快照】：标题、摘要、分区名、封面、标签全部存当时的值（_snapshot 后缀），
 * 之后主人继续改草稿，这个快照一个字都不变 —— 这就是"发布版本不可覆盖"的底层。
 * 不可变 → 无 version 列、无 updated_at → 不标 @Version。
 */
@TableName("post_version")
public class PostVersionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;
    private Integer versionNo;                 // 第几版（与 post_id 组成唯一键）
    private String titleSnapshot;
    private String summarySnapshot;
    private Long channelIdSnapshot;
    private String channelNameSnapshot;        // 分区名也快照，避免改名影响历史版本
    private String coverMediaPublicIdSnapshot;
    private String tagIdsSnapshotJson;         // 标签快照（JSON，L21 用）
    private String contentOrigin;              // HUMAN_ONLY / AI_ASSISTED / AI_GENERATED
    private String moderationStatus;           // NOT_REQUIRED / PENDING / APPROVED / ...
    private Long ownerApprovedByUserId;        // 谁批准的（审计）
    private Instant ownerApprovedAt;
    private Instant publishedAt;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getTitleSnapshot() {
        return titleSnapshot;
    }

    public void setTitleSnapshot(String titleSnapshot) {
        this.titleSnapshot = titleSnapshot;
    }

    public String getSummarySnapshot() {
        return summarySnapshot;
    }

    public void setSummarySnapshot(String summarySnapshot) {
        this.summarySnapshot = summarySnapshot;
    }

    public Long getChannelIdSnapshot() {
        return channelIdSnapshot;
    }

    public void setChannelIdSnapshot(Long channelIdSnapshot) {
        this.channelIdSnapshot = channelIdSnapshot;
    }

    public String getChannelNameSnapshot() {
        return channelNameSnapshot;
    }

    public void setChannelNameSnapshot(String channelNameSnapshot) {
        this.channelNameSnapshot = channelNameSnapshot;
    }

    public String getCoverMediaPublicIdSnapshot() {
        return coverMediaPublicIdSnapshot;
    }

    public void setCoverMediaPublicIdSnapshot(String coverMediaPublicIdSnapshot) {
        this.coverMediaPublicIdSnapshot = coverMediaPublicIdSnapshot;
    }

    public String getTagIdsSnapshotJson() {
        return tagIdsSnapshotJson;
    }

    public void setTagIdsSnapshotJson(String tagIdsSnapshotJson) {
        this.tagIdsSnapshotJson = tagIdsSnapshotJson;
    }

    public String getContentOrigin() {
        return contentOrigin;
    }

    public void setContentOrigin(String contentOrigin) {
        this.contentOrigin = contentOrigin;
    }

    public String getModerationStatus() {
        return moderationStatus;
    }

    public void setModerationStatus(String moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public Long getOwnerApprovedByUserId() {
        return ownerApprovedByUserId;
    }

    public void setOwnerApprovedByUserId(Long ownerApprovedByUserId) {
        this.ownerApprovedByUserId = ownerApprovedByUserId;
    }

    public Instant getOwnerApprovedAt() {
        return ownerApprovedAt;
    }

    public void setOwnerApprovedAt(Instant ownerApprovedAt) {
        this.ownerApprovedAt = ownerApprovedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
