package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * contribution_ticket 表的 DO —— 队列里的一个席位（票）。因果链的节点（V012）。
 *
 * ★ 顺序不靠时间戳，靠这两个字段：
 *   sequenceNo             第几棒（1 起）。与 sessionId 组唯一键 → 同一会话不可能有两个第 N 棒。
 *   predecessorTicketId    前一棒（首棒为 null）。这就是「因果序」的物化——
 *                          B 只有拿到 A 的接力棒才可能入队，所以 B 一定在 A 之后，与谁手快无关。
 *
 * ★ requiredAgentId（NOT NULL）「这一棒只能由这个机娘来写」：
 *   【不由客户端传入】。join 时由「谁成功消费了那张令牌」决定——服务端从 Chain 3 的 agent 令牌
 *   解出 AgentIdentity.agentAccountId()，同时写进 handoff_token.consumed_by_agent_id 与本列。
 *   即：令牌签发时无记名（bearer，谁拿到谁能用），被消费的那一刻实名化，同时定下新席位归属人。
 *   为什么必须记：APPROVAL_RECORD 冻结了「retry 只允许原机娘，但允许换新对话」——这一列是为 L18 存在的。
 *
 * activeAttemptId 建列不建外键：contribution_attempt 表是 L16 建的，届时再 ALTER 补 FK。
 */
@TableName("contribution_ticket")
public class ContributionTicketDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketCode;
    private Long sessionId;
    private Integer sequenceNo;

    private Long requiredAgentId;
    private String sourceTool;
    private String clientRunId;

    private Long predecessorTicketId;
    private String status;
    private Long activeAttemptId;

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

    public String getTicketCode() {
        return ticketCode;
    }

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public Long getRequiredAgentId() {
        return requiredAgentId;
    }

    public void setRequiredAgentId(Long requiredAgentId) {
        this.requiredAgentId = requiredAgentId;
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

    public Long getPredecessorTicketId() {
        return predecessorTicketId;
    }

    public void setPredecessorTicketId(Long predecessorTicketId) {
        this.predecessorTicketId = predecessorTicketId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getActiveAttemptId() {
        return activeAttemptId;
    }

    public void setActiveAttemptId(Long activeAttemptId) {
        this.activeAttemptId = activeAttemptId;
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
