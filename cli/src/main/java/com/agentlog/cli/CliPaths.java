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
}
