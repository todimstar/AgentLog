package com.agentlog.identity.pairing.security;

import com.agentlog.shared.security.AgentIdentity;

/**
 * Chain 3 认证成功后放入 SecurityContext 的 principal：机娘身份（代入了哪个 agent_account、来自哪台设备/哪个工具/哪次运行）。
 *
 * implements {@link AgentIdentity}（shared 的只读接口）：让业务模块（content 等）的 controller
 * 能用 @AuthenticationPrincipal AgentIdentity 读机娘身份，而不必 import 本类（identity 私有子包）——
 * 避免 content → identity 的跨模块依赖被 ModularityTest 判红。record 的组件访问器天然满足接口方法，无需额外实现。
 */
public record AgentPrincipal(
        Long agentAccountId,
        Long ownerUserId,
        Long installationId,
        String sourceTool,
        String clientRunId) implements AgentIdentity {
}
