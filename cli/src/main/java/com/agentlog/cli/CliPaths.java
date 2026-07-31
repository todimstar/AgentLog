package com.agentlog.cli;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CLI 本地目录解析。
 *
 * 优先级：环境变量 {@code AGENTLOG_HOME} → 否则用户目录下的 {@code ~/.agentlog}。
 *
 * 为什么支持 AGENTLOG_HOME 覆盖（L12 前瞻设计）：
 *   同一台机器可能并存多个 CLI 实例——比如 Claude Code 和 Codex 各跑一个，或未来以 skill 形式
 *   分发时每个 skill 一个 CLI。它们必须各自隔离配置与凭据（各自的 installationCode → 各自配对 →
 *   各自 owner 令牌）。默认共享 ~/.agentlog；需要隔离时，每个实例设不同的 AGENTLOG_HOME 即可。
 *   （机娘身份的隔离是另一层：L13 的 assume 按 agent + tool + run 维度区分，与本目录无关。）
 */
public final class CliPaths {

    private CliPaths() {
    }

    public static Path home() {
        String override = System.getenv("AGENTLOG_HOME");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"), ".agentlog");
    }

    public static Path configFile() {
        return home().resolve("config.json");
    }

    public static Path credentialsFile() {
        return home().resolve("credentials.json");
    }

    /**
     * 协作票的本地状态目录 {@code ~/.agentlog/state/tickets/}（L15，形状对齐
     * Pack {@code 07-cli/schemas/ticket-state.schema.json}）。
     *
     * <p>存的是「我参与的这一棒叫什么、当前什么状态、下一棒的尾令牌是什么」。
     * 有了它，同一台机器上的下一个 AI 对话可以直接 {@code agentlog collab join}（不带 --handoff）
     * 接着写，不必主人手工复制粘贴令牌。
     *
     * <p>⚠️ <b>这里存的是明文，不是加密</b>（同 {@code credentials.json}）：
     * 只靠「文件权限 + 绝不打印」两道非加密的墙。真正的静态加密要托管给系统 Keychain，
     * 那是 credential-policy 的 V1+。讲"凭据保护"时务必说清「明文 + 权限 + 不打印」。
     * 令牌本身 24h 过期且一次性消费——安全模型是「泄漏了很快贬值」，不是「绝不泄漏」。
     */
    public static Path ticketStateDir() {
        return home().resolve("state").resolve("tickets");
    }

    /** 单张票的状态文件，如 {@code ~/.agentlog/state/tickets/CT-3f9a1c07b4e2d581.json}。 */
    public static Path ticketStateFile(String ticketCode) {
        return ticketStateDir().resolve(ticketCode + ".json");
    }
}
