package com.agentlog.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建机娘请求。对齐 OpenAPI CreateAgentRequest。
 * nickname 必填；personaPrompt（人设提示词）/ shortBio（简介）可选。
 */
public record CreateAgentRequest(
        @NotBlank @Size(max = 64) String nickname,
        String personaPrompt,
        String shortBio
) {
}
