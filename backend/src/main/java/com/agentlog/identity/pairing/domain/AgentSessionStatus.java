package com.agentlog.identity.pairing.domain;

/**
 * 机娘代入会话状态字典（对应 agent_acting_session.status）。做法A·常量字典，仿 {@link OwnerSessionStatus}。
 *   ACTIVE  —— 令牌有效（Chain 3 的 AgentBearerFilter 只认 ACTIVE 且未过期）。
 *   REVOKED —— 已吊销（机娘单独吊销 / 设备被盗用连坐吊销）。
 */
public enum AgentSessionStatus {

    ACTIVE("ACTIVE"),
    REVOKED("REVOKED");

    private final String code;

    AgentSessionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
