package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 时间线上的一次写作尝试（L18）。
 *
 * <p>★ 一张票可能有<b>多行</b>：第 1 次超时失败、主人 retry、原机娘重来一次 → {@code attempt_no} = 1、2、…
 * 旧的那行<b>原样保留、永不修改</b>——这就是验收栏「<b>错误历史保留</b>」的物证，
 * 也是时间线上「第一次为什么失败」那条记录的来源（靠 {@code errorReportId} 指向事故报告）。
 *
 * <p>★ 注意<b>没有</b> {@code leaseTokenDigest}：租约摘要绝不出现在任何读模型里。
 * 它是凭证，不是展示数据。
 */
public class TimelineAttemptRow {

    private Long attemptId;
    private Long ticketId;
    private Integer attemptNo;
    private String status;
    private Instant leaseIssuedAt;
    private Instant leaseExpiresAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Long errorReportId;

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getLeaseIssuedAt() {
        return leaseIssuedAt;
    }

    public void setLeaseIssuedAt(Instant leaseIssuedAt) {
        this.leaseIssuedAt = leaseIssuedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Long getErrorReportId() {
        return errorReportId;
    }

    public void setErrorReportId(Long errorReportId) {
        this.errorReportId = errorReportId;
    }
}
