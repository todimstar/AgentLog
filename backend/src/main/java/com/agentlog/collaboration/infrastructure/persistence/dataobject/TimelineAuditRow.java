package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import java.time.Instant;

/**
 * 时间线的一条流水（L18）。来自 <b>audit 模块</b>的 {@code audit_record} + 机娘展示名。
 *
 * <h3>★ 这是本课「跨模块只读投影」的主角</h3>
 * {@code audit_record} 属于 audit 模块、{@code agent_account} 属于 identity 模块，
 * 而本查询在 collaboration。三个模块的表 join 在一条 SQL 里，但<b>一个类都不 import</b>——
 * 靠的是 SQL 只依赖<b>表结构</b>，不依赖<b>Java 类型</b>。{@code ModularityTest} 检查的是字节码引用，
 * 所以这样不会被打红。
 *
 * <p>⚠️ 代价要诚实说：这条 SQL 与 audit 模块的表结构<b>隐式耦合</b>了，
 * 而编译器不会替你检查——audit 改列名，这里运行时才炸。
 * 项目接受这个代价（D-05），因为替代方案（为只读架 Facade）成本更高、收益为零。
 *
 * <h3>★ 这张表本课才第一次有完整数据</h3>
 * {@code COLLAB_STARTED} / {@code HANDOFF_CLAIMED} / {@code LEASE_CLAIMED} 三个动作
 * 从 V014 起就在 CHECK 值集里，却<b>从来没有代码产生过</b>。所以在 L18 之前，
 * 时间线只有「提交」和「超时」——页面会显示一条协作凭空从「第 1 棒提交成功」开始。
 */
public class TimelineAuditRow {

    private Long id;
    private String actionType;
    private String summary;
    private String detailJson;
    private Long ticketId;
    private Long agentId;
    private String agentNickname;
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetailJson() {
        return detailJson;
    }

    public void setDetailJson(String detailJson) {
        this.detailJson = detailJson;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getAgentNickname() {
        return agentNickname;
    }

    public void setAgentNickname(String agentNickname) {
        this.agentNickname = agentNickname;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
