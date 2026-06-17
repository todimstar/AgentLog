package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * post_version_block 表的 DO —— 📸不可变线上正文。对应 V005 的 post_version_block 表。
 *
 * 发布时把 draft_block 逐块【复制】成快照存到这里（content_snapshot）。
 * 读者访问公开帖详情，读的就是这张表（不是 draft_block）——所以改草稿不影响线上。
 * source_draft_block_id 记着"我复制自哪个草稿块"（可追溯）。
 * 不可变 → 无 version 列 → 不标 @Version。
 */
@TableName("post_version_block")
public class PostVersionBlockDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postVersionId;                // 属于哪个发布版本
    private Long sourceDraftBlockId;           // 复制自哪个草稿块（可追溯）
    private Long contributionId;               // 间接指回原始贡献
    private String authorType;
    private Long authorUserId;
    private Long authorAgentId;
    private String sourceTool;
    private Integer displayOrder;
    private String contentSnapshot;            // 正文快照（MEDIUMTEXT），发布瞬间冻结

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPostVersionId() {
        return postVersionId;
    }

    public void setPostVersionId(Long postVersionId) {
        this.postVersionId = postVersionId;
    }

    public Long getSourceDraftBlockId() {
        return sourceDraftBlockId;
    }

    public void setSourceDraftBlockId(Long sourceDraftBlockId) {
        this.sourceDraftBlockId = sourceDraftBlockId;
    }

    public Long getContributionId() {
        return contributionId;
    }

    public void setContributionId(Long contributionId) {
        this.contributionId = contributionId;
    }

    public String getAuthorType() {
        return authorType;
    }

    public void setAuthorType(String authorType) {
        this.authorType = authorType;
    }

    public Long getAuthorUserId() {
        return authorUserId;
    }

    public void setAuthorUserId(Long authorUserId) {
        this.authorUserId = authorUserId;
    }

    public Long getAuthorAgentId() {
        return authorAgentId;
    }

    public void setAuthorAgentId(Long authorAgentId) {
        this.authorAgentId = authorAgentId;
    }

    public String getSourceTool() {
        return sourceTool;
    }

    public void setSourceTool(String sourceTool) {
        this.sourceTool = sourceTool;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getContentSnapshot() {
        return contentSnapshot;
    }

    public void setContentSnapshot(String contentSnapshot) {
        this.contentSnapshot = contentSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
