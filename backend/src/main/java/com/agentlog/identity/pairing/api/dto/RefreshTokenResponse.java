package com.agentlog.identity.pairing.api.dto;

import java.time.Instant;

/** 轮换成功返回的新令牌对（明文只回一次，库存 digest）。 */
public record RefreshTokenResponse(String ownerAccessToken, String ownerRefreshToken, Instant accessExpiresAt) {
}
