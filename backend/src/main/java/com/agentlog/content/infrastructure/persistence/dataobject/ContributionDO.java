package com.agentlog.content.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * contribution 表的 DO —— 🔒不可变原始贡献。对应 V005 的 contribution 表。
 *
 * 它记录"谁、用什么工具、原始写了什么"，一旦写入【永不修改】。
 * 主人润色不会动它（动的是 draft_block 副本），多 AI 接力时每棒的原始产出都在这里可审计。
 * 不可变 → 没有 version 列、没有 updated_at → 不需要 @Version。
 *
 * OWNER 类型 = 主人自己发帖（L06）；AGENT 类型 + author_agent_id/source_tool/client_run_id = 机娘投稿（L14 启用）。
 * ACPP 多机娘接力的 session_id/ticket_id 仍留到 L15+。
 */
@TableName("contribution")
public class ContributionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;                    // ACPP 协作会话（L15+，本课 null）
    private Long ticketId;                     // ACPP 席位（L15+，本课 null）
    private String authorType;                 // OWNER / AGENT
    private Long authorUserId;                 // author_type=OWNER 时填
    private Long authorAgentId;                // author_type=AGENT 时填（L14 机娘投稿启用）
    private String sourceTool;                 // 来源工具（如 codex/claude-code；L14 机娘投稿启用）
    private String clientRunId;                // 本次运行 id（隔离键；L14 机娘投稿启用）
    private String rawContent;                 // 原始正文（MEDIUMTEXT），永不覆盖
    private String metadataJson;               // JSON 列，本课不用

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
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

    public String getClientRunId() {
        return clientRunId;
    }

    public void setClientRunId(String clientRunId) {
        this.clientRunId = clientRunId;
    }

    public String getRawContent() {
        return rawContent;
    }

    public void setRawContent(String rawContent) {
        this.rawContent = rawContent;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
