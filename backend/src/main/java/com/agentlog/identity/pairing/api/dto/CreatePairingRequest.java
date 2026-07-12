package com.agentlog.identity.pairing.api.dto;

import jakarta.validation.constraints.NotBlank;

/** CLI 发起配对请求。对齐 OpenAPI CreatePairingRequest。 */
public record CreatePairingRequest(
        @NotBlank String installationCode,
        @NotBlank String deviceName) {
}
