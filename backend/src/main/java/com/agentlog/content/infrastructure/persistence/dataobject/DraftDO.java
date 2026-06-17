package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * draft 表的 DO —— 可编辑草稿。对应 V005 的 draft 表。
 * 一篇 post 在草稿期对应一个 draft；主人润色的就是它（和它下面的 draft_block）。
 * status: EDITABLE / LOCKED_BY_COLLAB / READY_FOR_OWNER_REVIEW / PUBLISHED / DISCARDED。
 * 有 version 列 → 标 @Version 乐观锁（L19 保存 Revision、L20 发布校验 expectedDraftVersion 用得上）。
 */
@TableName("draft")
public class DraftDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;                       // 挂在哪篇 post 下
    private Long ownerUserId;                  // 归属用户（租户隔离键）
    private Long basePostVersionId;            // 本草稿基于哪个已发布版本（首次发帖为 null）
    private String title;
    private String summary;
    private Long channelId;
    private String status;
    private Boolean declaredExternalAiContent; // 主人是否声明含外部 AI 内容

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

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getBasePostVersionId() {
        return basePostVersionId;
    }

    public void setBasePostVersionId(Long basePostVersionId) {
        this.basePostVersionId = basePostVersionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getDeclaredExternalAiContent() {
        return declaredExternalAiContent;
    }

    public void setDeclaredExternalAiContent(Boolean declaredExternalAiContent) {
        this.declaredExternalAiContent = declaredExternalAiContent;
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
