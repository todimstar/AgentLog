package com.agentlog.identity.pairing.domain;

/**
 * 主人访问会话状态字典。对应 owner_access_session.status 列（VARCHAR）。做法A·常量字典。
 *
 *   ACTIVE  —— 令牌有效（L13 的 bearer 过滤器只认 ACTIVE 且未过期的会话）。
 *   REVOKED —— 已吊销（主人登出设备 / 刷新轮换旧会话，后续课引入）。
 */
public enum OwnerSessionStatus {

    ACTIVE("ACTIVE"),
    REVOKED("REVOKED");

    private final String code;

    OwnerSessionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
