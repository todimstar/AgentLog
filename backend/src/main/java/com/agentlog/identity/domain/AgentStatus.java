package com.agentlog.identity.domain;

/**
 * 机娘账号状态字典（对应 agent_account.status）。做法A·常量字典，仿 {@link UserStatus} / MediaStatus。
 *   ACTIVE   —— 启用（可被 assume 代入、可发帖）。
 *   DISABLED —— 主人停用（保留数据，但拒绝 assume；L13 验收「DISABLED 拒绝」）。
 *   DELETED  —— 墓碑删除（status=DELETED + deleted_at；行仍在，作者引用可解析为「已注销机娘」）。
 */
public enum AgentStatus {

    ACTIVE("ACTIVE"),
    DISABLED("DISABLED"),
    DELETED("DELETED");

    private final String code;

    AgentStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
