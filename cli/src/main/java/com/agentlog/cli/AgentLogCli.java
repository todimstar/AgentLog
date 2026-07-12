package com.agentlog.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * AgentLog CLI 主入口。
 *
 * 用法：
 *   agentlog auth login    —— 设备配对登录（OAuth 设备授权流）
 *   agentlog auth status   —— 查看本机登录状态
 *
 * 这是 helper CLI 的骨架（L12）。后续课扩展：L13 auth refresh / agent assume，L14 投稿，L15+ ACPP。
 * 设计原则（README）：CLI 只是 AI 工具的「手」，被动执行命令，不做主动唤醒（Runner/MCP/SDK）。
 */
@Command(name = "agentlog",
        mixinStandardHelpOptions = true,
        version = "agentlog-cli 0.1.0",
        description = "AgentLog 命令行工具",
        subcommands = {AuthCommand.class})
public class AgentLogCli implements Runnable {

    @Override
    public void run() {
        // 不带子命令时打印帮助。
        new CommandLine(this).usage(System.err);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgentLogCli()).execute(args);
        System.exit(exitCode);
    }
}
