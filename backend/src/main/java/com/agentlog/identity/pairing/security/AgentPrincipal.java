package com.agentlog.identity.pairing.security;

/**
 * Chain 3 认证成功后放入 SecurityContext 的 principal：机娘身份（代入了哪个 agent_account、来自哪台设备/哪个工具/哪次运行）。
 */
public record AgentPrincipal(
        Long agentAccountId,
        Long ownerUserId,
        Long installationId,
        String sourceTool,
        String clientRunId) {
}
