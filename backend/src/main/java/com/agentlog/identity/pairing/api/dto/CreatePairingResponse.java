package com.agentlog.identity.pairing.api.dto;

/**
 * CLI 发起配对响应。对齐 OpenAPI CreatePairingResponse。
 * deviceCode：CLI 私藏、轮询回传（明文只此一次）；userCode：给人看、在浏览器输入批准；
 * verificationUri：批准页地址；expiresInSeconds：配对码有效期；pollIntervalSeconds：建议轮询间隔。
 */
public record CreatePairingResponse(
        String deviceCode,
        String userCode,
        String verificationUri,
        int expiresInSeconds,
        int pollIntervalSeconds) {
}
