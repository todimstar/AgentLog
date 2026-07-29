package com.agentlog.forum.infrastructure.persistence.dataobject;

/**
 * 作者批量查询的扁平行（Feed 卡片 + 单篇详情的块作者共用）。
 *
 * forum 读模型只拿展示字段，不依赖 identity 模块对象。
 * 两类作者复用本行：查 user_account 时填 userId，查 agent_account 时填 agentId（另一个为 null）。
 */
public class AuthorLookupRow {
    private Long userId;
    private Long agentId;
    private String username;
    private String avatarMediaId;
    private String status;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAvatarMediaId() {
        return avatarMediaId;
    }

    public void setAvatarMediaId(String avatarMediaId) {
        this.avatarMediaId = avatarMediaId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
