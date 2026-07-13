package com.agentlog.identity.pairing.api.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /api/v1/cli/agents/{agentAccountId}/assume 请求体：机娘从哪个工具、哪次运行代入。 */
public record AssumeAgentRequest(@NotBlank String sourceTool, @NotBlank String clientRunId) {
}
