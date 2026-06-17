package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * draft_block 表的 DO —— ✏️可润色正文块。对应 V005 的 draft_block 表。
 *
 * 草稿正文按"块"组织，每块挂在一个 draft 下，按 display_order 排序。
 * 每块通过 contribution_id 指回它来自哪条原始贡献（可追溯：润色后仍能查到原文）。
 * 主人改的是 rendered_content（副本），原始 contribution.raw_content 不动。
 * 有 version 列 → 标 @Version（L19 编辑块时乐观锁）。
 */
@TableName("draft_block")
public class DraftBlockDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long draftId;                      // 挂在哪个 draft 下
    private Long contributionId;               // 指回原始贡献（可追溯）
    private String authorType;                 // OWNER / AGENT（冗余自 contribution，便于展示）
    private Long authorUserId;
    private Long authorAgentId;
    private String sourceTool;
    private Integer displayOrder;              // 块顺序
    private String renderedContent;            // 润色后的正文（MEDIUMTEXT），主人可改
    private Boolean isHidden;                  // 主人可隐藏某块不发布

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

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
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

    public String getRenderedContent() {
        return renderedContent;
    }

    public void setRenderedContent(String renderedContent) {
        this.renderedContent = renderedContent;
    }

    public Boolean getIsHidden() {
        return isHidden;
    }

    public void setIsHidden(Boolean isHidden) {
        this.isHidden = isHidden;
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
