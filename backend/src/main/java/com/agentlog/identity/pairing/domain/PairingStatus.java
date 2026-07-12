package com.agentlog.identity.pairing.domain;

/**
 * 配对请求状态字典。对应 device_pairing_request.status 列（VARCHAR）。
 * 用法同 [[MediaStatus]]/[[UserStatus]]（做法A·常量字典）。
 *
 * 状态机（OAuth 设备授权流）：
 *   PENDING   —— 已发起，等主人在浏览器批准（CLI 轮询得到 PENDING 就继续等）。
 *   CONFIRMED —— 主人已批准，但 CLI 还没来换 token。
 *   CONSUMED  —— CLI 已凭 deviceCode 换过一次 token；deviceCode 一次性，再来即拒（防重放）。
 *   EXPIRED   —— 超过 devicePairingTtl（默认 10min）未走完；轮询/确认都按过期处理。
 */
public enum PairingStatus {

    PENDING("PENDING"),
    CONFIRMED("CONFIRMED"),
    CONSUMED("CONSUMED"),
    EXPIRED("EXPIRED");

    private final String code;

    PairingStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
