package com.agentlog.identity.pairing.api.dto;

import java.time.Instant;

/**
 * CLI 轮询换 token 响应。对齐 OpenAPI ExchangePairingResponse。
 * status：PENDING（还没批准，继续轮询）/ APPROVED（已批准，附 token）/ EXPIRED（配对码过期）。
 * 仅 APPROVED 时 token 字段非空。明文 token 只在此返回一次。
 * 注意：PENDING/EXPIRED 都是正常轮询结果（HTTP 200），不是错误——错误（不存在/已消费）才抛 ApiException。
 */
public record ExchangePairingResponse(
        String status,
        String ownerAccessToken,
        String ownerRefreshToken,
        Instant accessExpiresAt) {

    public static ExchangePairingResponse pending() {
        return new ExchangePairingResponse("PENDING", null, null, null);
    }

    public static ExchangePairingResponse expired() {
        return new ExchangePairingResponse("EXPIRED", null, null, null);
    }

    public static ExchangePairingResponse approved(String access, String refresh, Instant accessExpiresAt) {
        return new ExchangePairingResponse("APPROVED", access, refresh, accessExpiresAt);
    }
}
