package com.agentlog.identity.pairing.api.dto;

/** GET /api/v1/agent/whoami 响应：当前 AgentActingToken 代表的机娘身份。 */
public record AgentWhoamiResponse(
        Long agentAccountId,
        Long ownerUserId,
        Long installationId,
        String sourceTool,
        String clientRunId) {
}
