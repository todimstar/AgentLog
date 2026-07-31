package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * submit 命令（L14 单机娘投稿）：把一段开发过程写成【草稿】投进 AgentLog，返回草稿 URL 给主人。
 *
 * 前提：先 agent assume 代入一个机娘（拿到 acting token 存本地）。本命令带该 acting token 调
 *   POST /api/v1/agent/drafts（Chain 3 保护）。机娘只能投草稿——【绝无 publish】，发布权属主人。
 *
 * 输出规则（cli-spec）：stdout 出机器可读 JSON（含 draftUrl，供 Skill 回给主人）；
 *   stderr 出人类诊断；**绝不打印 token**（沿用 L13 铁律）。
 *
 * 正文走 --file（对齐 Pack 08-skill 蓝图的 --file contribution.md）：长文走文件更实用，也便于 Skill 落盘证据后投递。
 */
@Command(name = "submit", description = "以当前代入的机娘投一篇草稿（返回草稿 URL；机娘不能发布）")
public class SubmitCommand implements Callable<Integer> {

    @Option(names = {"--file", "-f"}, required = true, description = "正文文件路径（草稿正文，UTF-8）")
    Path file;

    @Option(names = {"--title", "-T"}, required = true, description = "草稿标题")
    String title;

    @Option(names = {"--channel", "-c"}, required = true,
            description = "分区 slug（如 dev / ai-collab / ops-review），也兼容数字 id")
    String channel;

    @Option(names = {"--summary", "-s"}, description = "摘要（可选）")
    String summary;

    @Option(names = "--server", description = "后端地址（缺省用已记住的）")
    String server;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() {
        // 1. 取当前代入机娘的 acting token（无则提示先 assume；过期则提示重新 assume——短命无 refresh）。
        CredentialStore store = new CredentialStore();
        String actingToken = store.getActingToken();
        if (actingToken == null) {
            System.err.println("[FAIL] 尚未代入机娘。先运行 agentlog agent assume --agent-id <id>。");
            return 1;
        }
        if (!store.isActingValid(Instant.now())) {
            System.err.println("[FAIL] 机娘令牌已过期。重新运行 agentlog agent assume（机娘令牌短命无 refresh）。");
            return 1;
        }

        // 2. 读正文文件（UTF-8）。
        String content;
        try {
            content = Files.readString(file);
        } catch (Exception e) {
            System.err.println("[FAIL] 读取正文文件失败: " + file + " — " + e.getMessage());
            return 1;
        }
        if (content.isBlank()) {
            System.err.println("[FAIL] 正文为空: " + file);
            return 1;
        }

        // 3. 组请求体（作者身份由 acting token 在服务端派生，不在 body 里传）。
        CliConfig config = new CliConfig();
        if (server != null && !server.isBlank()) {
            config.setServerBaseUrl(server);
        }
        ApiClient api = new ApiClient(config.serverBaseUrl());

        // L15 起 --channel 收 slug（dev / ai-collab / ops-review），CLI 侧调 /public/channels 解析成 id。
        // 修的是 L14 登记的「悬空引用」：命令强制要一个数字 id，但整个 CLI 没有任何命令能告诉你它是几。
        // 兼容纯数字，老用法不破。详见 Channels 的说明。
        Long channelId = Channels.resolve(api, channel);
        if (channelId == null) {
            return 1;
        }

        ObjectNode body = mapper.createObjectNode()
                .put("title", title)
                .put("channelId", channelId)
                .put("content", content);
        if (summary != null && !summary.isBlank()) {
            body.put("summary", summary);
        }

        ApiClient.Result res = api.postJson("/api/v1/agent/drafts", body.toString(), actingToken);

        if (res.status() == 401) {
            System.err.println("[FAIL] 机娘令牌无效或已过期（AGENT_TOKEN_*）。重新 agent assume 后再试。");
            return 1;
        }
        if (!res.ok()) {
            System.err.println("[FAIL] 投稿失败: HTTP " + res.status()
                    + " " + res.body().path("code").asText(""));
            return 1;
        }

        // 4. 成功：stdout 出机器可读 JSON（draftId + draftUrl），供 Skill 回主人。绝不含 token。
        JsonNode b = res.body();
        String draftUrl = b.path("draftUrl").asText("");
        long draftId = b.path("draft").path("draftId").asLong();
        System.err.println("[OK] 草稿已投递。提醒：这仍是草稿，需主人审稿后发布（机娘不能发布）。");
        System.err.println("     审稿地址: " + draftUrl);
        System.out.println(mapper.createObjectNode()
                .put("status", "ok")
                .put("draftId", draftId)
                .put("draftUrl", draftUrl)
                .toString());
        return 0;
    }
}
