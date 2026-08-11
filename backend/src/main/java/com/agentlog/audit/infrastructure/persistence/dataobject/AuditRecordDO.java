package com.agentlog.audit.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * audit_record 表的 DO（V014）。
 *
 * ★ 流水表：只 INSERT 永不 UPDATE；没有 updatedAt / version；没有唯一键。
 * 「谁、何时、做了什么」—— 不含正文，不是内容历史。
 * 内容历史 → post_version（已发布快照）/ draft_revision（草稿编辑史，L19）。
 */
@TableName("audit_record")
public class AuditRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private Long sessionId;
    private Long ticketId;
    private Long agentId;

    /** 对应 ck_audit_action_type CHECK 约束中的合法值。 */
    private String actionType;
    private String summary;

    /** 不需要查的细节进这里；需要查的提成正式列。 */
    private String detailJson;

    private Instant createdAt;   // 只有 createdAt，没有 updatedAt

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String detailJson) { this.detailJson = detailJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
