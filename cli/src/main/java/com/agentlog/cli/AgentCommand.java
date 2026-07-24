package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * agents/agent 命令组：list（列出我的机娘）+ assume（代入机娘拿 acting token）。
 *
 * 输出规则（cli-spec）：stdout 出机器可读 JSON；stderr 出人类诊断；**绝不打印 token**。
 *
 * 两条命令都要 owner 令牌认证（Chain 2·Bearer）：
 *   - list：GET /cli/agents，列出当前 owner 名下机娘，供挑一个去 assume；
 *   - assume：POST /cli/agents/{id}/assume，用 owner 令牌换一把代表该机娘的短命 AgentActingToken，
 *     存 credentials.json 的 actingToken 位（不打印），过期用 owner 令牌重新 assume（无 refresh）。
 */
@Command(name = "agents", aliases = "agent", description = "机娘相关命令（列出 / 代入）",
        subcommands = {AgentCommand.ListAgents.class, AgentCommand.Assume.class})
public class AgentCommand implements Runnable {

    @Override
    public void run() {
        System.err.println("用法: agentlog agents [list|assume]");
    }

    /** 取当前 owner access token；无凭据/已过期时返回 null 并打印诊断（提示去 login/refresh）。 */
    private static String requireAccessToken() {
        CredentialStore store = new CredentialStore();
        String token = store.getAccessToken();
        if (token == null) {
            System.err.println("[FAIL] 未登录。先运行 agentlog auth login。");
            return null;
        }
        if (!store.isAccessValid(Instant.now())) {
            System.err.println("[FAIL] Access Token 已过期。先运行 agentlog auth refresh。");
            return null;
        }
        return token;
    }

    @Command(name = "list", description = "列出当前登录 owner 名下的机娘")
    static class ListAgents implements Callable<Integer> {

        @Option(names = "--server", description = "后端地址（缺省用已记住的）")
        String server;

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Integer call() {
            String token = requireAccessToken();
            if (token == null) {
                return 1;
            }
            CliConfig config = new CliConfig();
            if (server != null && !server.isBlank()) {
                config.setServerBaseUrl(server);
            }
            ApiClient api = new ApiClient(config.serverBaseUrl());
            ApiClient.Result res = api.getJson("/api/v1/cli/agents", token);
            if (!res.ok()) {
                System.err.println("[FAIL] 列出机娘失败: HTTP " + res.status());
                return 1;
            }
            JsonNode agents = res.body();
            // 人类诊断（stderr）：一行一个机娘，方便肉眼挑 id。
            if (!agents.isArray() || agents.isEmpty()) {
                System.err.println("（还没有机娘。去 web 设置页创建一个。）");
            } else {
                System.err.println("你的机娘：");
                for (JsonNode a : agents) {
                    System.err.printf("  #%d  %s  [%s]%n",
                            a.path("id").asLong(), a.path("nickname").asText(), a.path("status").asText());
                }
            }
            // 机器可读（stdout）：原样透传 AgentView 数组。
            System.out.println(agents.toString());
            return 0;
        }
    }

    @Command(name = "assume", description = "代入一个机娘，换取该机娘的运行时令牌（AgentActingToken）")
    static class Assume implements Callable<Integer> {

        @Option(names = {"--agent-id", "-a"}, required = true, description = "要代入的机娘 id（见 agents list）")
        long agentId;

        @Option(names = {"--tool", "-t"}, description = "来源工具标识（如 claude-code / cursor；缺省 agentlog-cli）")
        String sourceTool;

        @Option(names = {"--client-run-id", "-r"}, description = "本次运行 id（隔离键，缺省随机生成）")
        String clientRunId;

        @Option(names = "--server", description = "后端地址（缺省用已记住的）")
        String server;

        private final ObjectMapper mapper = new ObjectMapper();

        @Override
        public Integer call() {
            String token = requireAccessToken();
            if (token == null) {
                return 1;
            }
            CliConfig config = new CliConfig();
            if (server != null && !server.isBlank()) {
                config.setServerBaseUrl(server);
            }
            String tool = (sourceTool != null && !sourceTool.isBlank()) ? sourceTool : "agentlog-cli";
            String runId = (clientRunId != null && !clientRunId.isBlank())
                    ? clientRunId : "run-" + UUID.randomUUID();

            ApiClient api = new ApiClient(config.serverBaseUrl());
            ObjectNode body = mapper.createObjectNode().put("sourceTool", tool).put("clientRunId", runId);
            ApiClient.Result res = api.postJson("/api/v1/cli/agents/" + agentId + "/assume", body.toString(), token);

            if (res.status() == 404) {
                System.err.println("[FAIL] 机娘不存在或不属于你（AGENT_NOT_FOUND）。用 agents list 确认 id。");
                return 1;
            }
            if (!res.ok()) {
                System.err.println("[FAIL] 代入失败: HTTP " + res.status()
                        + " " + res.body().path("code").asText(""));
                return 1;
            }
            JsonNode b = res.body();
            long resolvedAgentId = b.path("agentAccountId").asLong(agentId);
            // 存机娘令牌（不打印！），供后续机娘身份的调用带 Bearer。
            new CredentialStore().saveActingToken(
                    resolvedAgentId,
                    b.get("agentActingToken").asText(),
                    b.path("expiresAt").asText(null));
            System.err.println("[OK] 已代入机娘 #" + agentId + "（工具=" + tool + "，运行=" + runId + "）。令牌已安全保存。");
            // stdout：不含 token，只报归属与过期。
            System.out.println(mapper.createObjectNode()
                    .put("status", "ok")
                    .put("agentAccountId", resolvedAgentId)
                    .put("sourceTool", tool)
                    .put("clientRunId", runId)
                    .put("expiresAt", b.path("expiresAt").asText(null))
                    .toString());
            return 0;
        }
    }
}
