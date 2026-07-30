package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * collaboration_session 表的 DO —— 一篇文章的一次多机娘接力全过程（V012）。
 *
 * 关键字段的设计意图：
 *   postTicket           对外标识（PT-16hex，随机不递增，防枚举）。自增 id 不出现在 API 里。
 *   postId / draftId     【首棒 submit 时才填】（L16）。L15 建 session 时为 null——
 *                        因为 APPROVAL_RECORD 冻结了「首棒失败不暴露空草稿」，提前建就会留孤儿行。
 *   plannedTitle/...     D-15 补列：start 收下的「打算写什么、投哪个分区」，暂存于此，首棒 submit 时消费。
 *   tailHandoffTokenId   「链尾那根悬空的接力棒」——下一个 AI 要拿的就是它。每次接力都换成新签发的。
 *   lastCompletedSequence 已完成到第几棒（L16 推进；L15 恒 0）。
 */
@TableName("collaboration_session")
public class CollaborationSessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String postTicket;
    private Long ownerUserId;

    private Long postId;
    private Long draftId;

    private String plannedTitle;
    private Long plannedChannelId;
    private String plannedSummary;

    private String status;
    private Long tailHandoffTokenId;
    private Integer lastCompletedSequence;

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

    public String getPostTicket() {
        return postTicket;
    }

    public void setPostTicket(String postTicket) {
        this.postTicket = postTicket;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getPostId() {
        return postId;
    }

    public void setPostId(Long postId) {
        this.postId = postId;
    }

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
    }

    public String getPlannedTitle() {
        return plannedTitle;
    }

    public void setPlannedTitle(String plannedTitle) {
        this.plannedTitle = plannedTitle;
    }

    public Long getPlannedChannelId() {
        return plannedChannelId;
    }

    public void setPlannedChannelId(Long plannedChannelId) {
        this.plannedChannelId = plannedChannelId;
    }

    public String getPlannedSummary() {
        return plannedSummary;
    }

    public void setPlannedSummary(String plannedSummary) {
        this.plannedSummary = plannedSummary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getTailHandoffTokenId() {
        return tailHandoffTokenId;
    }

    public void setTailHandoffTokenId(Long tailHandoffTokenId) {
        this.tailHandoffTokenId = tailHandoffTokenId;
    }

    public Integer getLastCompletedSequence() {
        return lastCompletedSequence;
    }

    public void setLastCompletedSequence(Integer lastCompletedSequence) {
        this.lastCompletedSequence = lastCompletedSequence;
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
