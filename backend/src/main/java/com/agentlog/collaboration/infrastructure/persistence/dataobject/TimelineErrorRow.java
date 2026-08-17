package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 时间线上的一次事故（L18）。来自 <b>reliability 模块</b>的 {@code error_report}（跨模块只读投影）。
 *
 * <h3>★ {@code suggestedActionsJson} 是本课前端的关键输入</h3>
 * L17 写入时已经按「首棒 / 中间棒」给了不同建议：
 * <pre>
 *   首棒失败   → ["TERMINATE_SESSION"]                  （post/draft 从未创建，没东西可救）
 *   中间棒失败 → ["RETRY_TICKET", "TERMINATE_SESSION"]  （草稿还在，可以让原机娘换新对话重来）
 * </pre>
 * 时间线页的按钮<b>由这个字段驱动，不写死</b>——后端改建议，前端自动跟着变。
 *
 * <p>★ 为什么值得这样做：按钮写死意味着「什么时候能 retry」这个规则<b>存在两份</b>
 * （后端一份、前端一份），而两份规则一定会漂移。让服务端说了算，前端只负责渲染。
 */
public class TimelineErrorRow {

    private Long id;
    private Long ticketId;
    private Long attemptId;
    private String errorType;
    private String failedStage;
    private String summary;
    private String suggestedActionsJson;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(Long attemptId) {
        this.attemptId = attemptId;
    }

    public String getErrorType() {
        return errorType;
    }

    public void setErrorType(String errorType) {
        this.errorType = errorType;
    }

    public String getFailedStage() {
        return failedStage;
    }

    public void setFailedStage(String failedStage) {
        this.failedStage = failedStage;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSuggestedActionsJson() {
        return suggestedActionsJson;
    }

    public void setSuggestedActionsJson(String suggestedActionsJson) {
        this.suggestedActionsJson = suggestedActionsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
