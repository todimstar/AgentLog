package com.agentlog.identity.pairing.domain;

/**
 * 客户端安装状态字典。对应 client_installation.status 列（VARCHAR）。做法A·常量字典。
 *
 *   PENDING  —— 已注册设备但尚未配对成功（owner_user_id 仍空）。
 *   ACTIVE   —— 配对确认后绑定到主人，可持令牌调用。
 *   DISABLED —— 主人吊销该设备（后续课引入）。
 */
public enum InstallationStatus {

    PENDING("PENDING"),
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED");

    private final String code;

    InstallationStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
