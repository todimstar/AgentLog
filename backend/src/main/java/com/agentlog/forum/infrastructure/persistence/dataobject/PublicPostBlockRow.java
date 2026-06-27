package com.agentlog.forum.infrastructure.persistence.dataobject;

/**
 * 单篇详情查询的"正文块"行对象（forum 只读投影）。
 * 从 post_version_block 读快照字段（content_snapshot），非草稿。
 */
public class PublicPostBlockRow {
    private Long id;
    private Integer displayOrder;
    private String contentSnapshot;
    private String sourceTool;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public String getContentSnapshot() { return contentSnapshot; }
    public void setContentSnapshot(String contentSnapshot) { this.contentSnapshot = contentSnapshot; }
    public String getSourceTool() { return sourceTool; }
    public void setSourceTool(String sourceTool) { this.sourceTool = sourceTool; }
}