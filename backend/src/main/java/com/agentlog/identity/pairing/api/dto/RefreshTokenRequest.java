package com.agentlog.identity.pairing.api.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/cli/auth/refresh 请求体：CLI 提交长期 refresh token 换新。 */
public record RefreshTokenRequest(@NotBlank String refreshToken) {
}
