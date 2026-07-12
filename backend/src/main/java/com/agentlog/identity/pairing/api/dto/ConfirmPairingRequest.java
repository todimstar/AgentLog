package com.agentlog.identity.pairing.api.dto;

import jakarta.validation.constraints.NotBlank;

/** 浏览器确认配对请求（主人输入 CLI 显示的 userCode）。 */
public record ConfirmPairingRequest(
        @NotBlank String userCode) {
}
