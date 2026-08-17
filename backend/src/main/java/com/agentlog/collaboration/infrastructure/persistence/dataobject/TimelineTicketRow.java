package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 时间线上的一个席位（L18）。票 + 它归属机娘的展示名，一次查出来。
 *
 * <p>★ {@code requiredAgentNickname} 来自 <b>identity 模块</b>的 {@code agent_account} 表——
 * 这是<b>跨模块只读 SQL 投影</b>（D-05 约定的正路），不 import identity 的任何类。
 * 与 L14 的 {@code AuthorLookupRow} 同构。
 *
 * <p>★ 为什么读可以直接查别人的表，写却必须走 Facade（L16 ContentFacade / L17 CollaborationFacade）：
 * Facade 的价值是<b>保证事务边界与不变量</b>（{@code appendAgentContribution} 用
 * {@code Propagation.MANDATORY} 强制必须在事务里）。<b>读不改变任何东西</b>，读错了最多显示错，
 * 不会写坏数据——为它架一层 Facade 是拿成本换不存在的收益。
 */
public class TimelineTicketRow {

    private Long ticketId;
    private String ticketCode;
    private Integer sequenceNo;
    private String status;
    private Long requiredAgentId;
    private String requiredAgentNickname;
    private Long predecessorTicketId;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getTicketCode() {
        return ticketCode;
    }

    public void setTicketCode(String ticketCode) {
        this.ticketCode = ticketCode;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getRequiredAgentId() {
        return requiredAgentId;
    }

    public void setRequiredAgentId(Long requiredAgentId) {
        this.requiredAgentId = requiredAgentId;
    }

    public String getRequiredAgentNickname() {
        return requiredAgentNickname;
    }

    public void setRequiredAgentNickname(String requiredAgentNickname) {
        this.requiredAgentNickname = requiredAgentNickname;
    }

    public Long getPredecessorTicketId() {
        return predecessorTicketId;
    }

    public void setPredecessorTicketId(Long predecessorTicketId) {
        this.predecessorTicketId = predecessorTicketId;
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
