package com.agentlog.identity.pairing.api.dto;

import jakarta.validation.constraints.NotBlank;

/** CLI 轮询换 token 请求。对齐 OpenAPI ExchangePairingRequest。 */
public record ExchangePairingRequest(
        @NotBlank String deviceCode) {
}
