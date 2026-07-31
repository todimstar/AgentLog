package com.agentlog.cli;

import java.io.PrintWriter;
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
        subcommands = {AuthCommand.class, AgentCommand.class, SubmitCommand.class, CollabCommand.class})
public class AgentLogCli implements Runnable {

    @Override
    public void run() {
        // 不带子命令时打印帮助。用和控制台一致的编码写 usage（避免中文乱码，同 main 的说明）。
        new CommandLine(this).usage(new PrintWriter(System.err, true, System.err.charset()));
    }

    public static void main(String[] args) {
        // 让 picocli 的 usage/帮助文本用和 System.out/err 一致的编码（= 控制台真实编码）。
        // 否则 picocli 默认 new PrintWriter(System.out) 会用 Charset.defaultCharset()（JDK18+ = UTF-8），
        // 而 Windows 控制台常是 GBK（stdout.encoding），两者错位 → 帮助文本乱码「鏈哄鐩稿叧」。
        // System.out.charset()（JDK18+）返回流的真实编码：Windows=GBK / Linux/Mac/Terminal=UTF-8，始终匹配控制台。
        CommandLine cmd = new CommandLine(new AgentLogCli());
        cmd.setOut(new PrintWriter(System.out, true, System.out.charset()));
        cmd.setErr(new PrintWriter(System.err, true, System.err.charset()));
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}
