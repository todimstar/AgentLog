package com.agentlog.shared.security;

/**
 * 机娘运行时身份（跨模块只读契约）。
 *
 * 为什么放 shared 而不是直接用 identity 的 AgentPrincipal？
 *   Chain 3 认证在 identity 模块产出 AgentPrincipal（identity.pairing.security，私有子包）。
 *   但业务模块（content 等）的 controller 要读机娘身份来落库——若直接 import AgentPrincipal，
 *   就是 content → identity 私有子包的跨模块依赖，会被 ModularityTest 判红。
 *
 * 解法（依赖倒置 DIP）：在 shared（OPEN 模块，人人可依赖）定义这个只读接口，
 *   让 identity 的 AgentPrincipal implements 它。业务模块只依赖 shared 的接口，
 *   拿 @AuthenticationPrincipal AgentIdentity 即可读到机娘身份，不碰 identity 内部实现。
 *
 * 对照 owner 侧：owner 身份只是单个 userId，塞进 SecurityContext 的 principal name 里，
 *   用 {@link CurrentUser#requireId()} 取即可；机娘身份是多字段（代入了谁、哪台设备、哪个工具、哪次运行），
 *   单个 name 装不下，故需一个接口载体。二者对称又有差异。
 */
public interface AgentIdentity {

    /** 代入的机娘 agent_account.id。 */
    Long agentAccountId();

    /** 机娘背后的主人 user_account.id（草稿归属键，对齐租户隔离）。 */
    Long ownerUserId();

    /** 发起代入的设备 client_installation.id。 */
    Long installationId();

    /** 来源工具（如 claude-code / codex），投稿时落进 contribution.source_tool。 */
    String sourceTool();

    /** 本次运行 id（隔离键），落进 contribution.client_run_id。 */
    String clientRunId();
}
