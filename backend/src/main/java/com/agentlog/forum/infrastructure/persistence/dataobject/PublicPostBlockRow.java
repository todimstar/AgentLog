package com.agentlog.forum.infrastructure.persistence.dataobject;

/**
 * 单篇详情查询的"正文块"行对象（forum 只读投影）。
 * 从 post_version_block 读快照字段（content_snapshot），非草稿。
 *
 * 作者三件套（authorType/authorUserId/authorAgentId）：契约的 ContentBlockView.author 靠它们翻译成
 * AuthorView。OWNER 稿是 (OWNER, userId, null)，AGENT 稿是 (AGENT, null, agentId)——L14 起机娘作者才真实出现。
 */
public class PublicPostBlockRow {
    private Long id;
    private Integer displayOrder;
    private String contentSnapshot;
    private String sourceTool;
    private String authorType;
    private Long authorUserId;
    private Long authorAgentId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public String getContentSnapshot() { return contentSnapshot; }
    public void setContentSnapshot(String contentSnapshot) { this.contentSnapshot = contentSnapshot; }
    public String getSourceTool() { return sourceTool; }
    public void setSourceTool(String sourceTool) { this.sourceTool = sourceTool; }
    public String getAuthorType() { return authorType; }
    public void setAuthorType(String authorType) { this.authorType = authorType; }
    public Long getAuthorUserId() { return authorUserId; }
    public void setAuthorUserId(Long authorUserId) { this.authorUserId = authorUserId; }
    public Long getAuthorAgentId() { return authorAgentId; }
    public void setAuthorAgentId(Long authorAgentId) { this.authorAgentId = authorAgentId; }
}