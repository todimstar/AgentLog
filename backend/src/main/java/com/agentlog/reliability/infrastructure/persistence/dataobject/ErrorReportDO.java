package com.agentlog.reliability.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * error_report 表的 DO（V014）。
 *
 * ★ 流水表特征（与 ticket/attempt 等状态表的三处关键不同）：
 * <ul>
 *   <li>只有 createdAt，没有 updatedAt / version —— 历史不能改，只 INSERT 永不 UPDATE</li>
 *   <li>没有唯一键约束 —— 同一件事可能发生两次（retry 后再失败一次）</li>
 *   <li>大量列可空 + JSON 兜底 —— 一张表要装各种形状的事件</li>
 * </ul>
 */
@TableName("error_report")
public class ErrorReportDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerUserId;
    private Long sessionId;
    private Long ticketId;
    private Long attemptId;
    private Long agentId;
    private String errorType;
    private String failedStage;
    private String summary;
    private String technicalDetail;

    /** JSON 数组，如 {@code ["RETRY_TICKET","TERMINATE"]}。供 CLI / Skill 自愈逻辑读取。 */
    private String suggestedActionsJson;

    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public Long getAttemptId() { return attemptId; }
    public void setAttemptId(Long attemptId) { this.attemptId = attemptId; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getFailedStage() { return failedStage; }
    public void setFailedStage(String failedStage) { this.failedStage = failedStage; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTechnicalDetail() { return technicalDetail; }
    public void setTechnicalDetail(String technicalDetail) { this.technicalDetail = technicalDetail; }
    public String getSuggestedActionsJson() { return suggestedActionsJson; }
    public void setSuggestedActionsJson(String suggestedActionsJson) { this.suggestedActionsJson = suggestedActionsJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
