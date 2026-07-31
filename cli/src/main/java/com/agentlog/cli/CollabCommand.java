package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * collab 命令族（L15 ACPP 接力）：{@code start} 开局、{@code join} 接力。
 *
 * <p>★ 这两条命令服务的真实场景：
 * <pre>
 *   [Claude Code 对话]  你：把这次开发记一篇
 *      assume 机娘A → agentlog collab start --title "..." --channel dev
 *      → 拿到下一棒令牌，交给下一个 AI
 *                      ↓  你复制粘贴（跨机）／同机直接读本地
 *   [Codex 对话]  你：接着写
 *      assume 机娘B → agentlog collab join [--handoff handoff_xxx]
 *      → 队列成型：#1(机娘A) → #2(机娘B)
 * </pre>
 * <b>本课到此为止</b>——真正写内容是 L16 的 {@code claim-turn} + {@code submit}。
 *
 * <p>★ 输出规则（Pack {@code 07-cli/cli-spec.md}）：
 * <b>stdout = 机器</b>（纯 JSON，供 Skill 管道消费）、<b>stderr = 人</b>（诊断与指引）。
 * 唯一例外：HandoffToken 明文必须进 stdout 的 JSON——它是 Skill 要回给主人的东西，
 * 而 cli-spec 也明说「HandoffToken 只在需要主人转交时输出一次」。
 * 其余令牌（owner / refresh / acting / lease）一律绝不出现在任何流里。
 */
@Command(name = "collab", description = "多机娘接力协作（开局 / 接力入队）",
        subcommands = {CollabCommand.Start.class, CollabCommand.Join.class})
public class CollabCommand implements Runnable {

    @Override
    public void run() {
        System.err.println("用法: agentlog collab <start|join>  （加 --help 看参数）");
    }

    // ——————————————————— 公共基座 ———————————————————

    /** 两条子命令共享的准备工作：取 acting token、拼 ApiClient、统一的错误提示。 */
    abstract static class Base implements Callable<Integer> {

        @Option(names = "--server", description = "后端地址（缺省用已记住的）")
        String server;

        final ObjectMapper mapper = new ObjectMapper();
        final TicketStateStore ticketStore = new TicketStateStore();

        /** 取当前代入机娘的 acting token；无/过期则打印指引并返回 null。 */
        String requireActingToken() {
            CredentialStore store = new CredentialStore();
            String token = store.getActingToken();
            if (token == null) {
                System.err.println("[FAIL] 尚未代入机娘。先运行: agentlog agent assume --agent-id <id>");
                return null;
            }
            if (!store.isActingValid(Instant.now())) {
                System.err.println("[FAIL] 机娘令牌已过期。重新运行: agentlog agent assume --agent-id <id>");
                System.err.println("       （机娘令牌默认 1 小时、短命且无 refresh——这是刻意的：泄漏了也很快贬值）");
                return null;
            }
            return token;
        }

        ApiClient api() {
            CliConfig config = new CliConfig();
            if (server != null && !server.isBlank()) {
                config.setServerBaseUrl(server);
            }
            return new ApiClient(config.serverBaseUrl());
        }

        /**
         * 把后端错误翻译成【人能照做的下一步】。
         *
         * 映射对齐 Pack {@code 08-skill/agentlog/references/error-actions.md} 的动作表——
         * Skill 读到这些提示就知道该 WAIT / STOP_AND_REPORT_OWNER / 索取新尾令牌 / 重新认证。
         * 只打印动作名不够，还要说清"具体该敲什么"，否则主人和机娘都得再猜一次。
         */
        int reportFailure(ApiClient.Result res) {
            String code = res.body().path("code").asText("");
            String detail = res.body().path("detail").asText("");
            switch (code) {
                case "ACPP_HANDOFF_CONSUMED" -> {
                    System.err.println("[FAIL] 这根接力棒已经被用掉了（一根棒子只能用一次）。");
                    System.err.println("       动作 ASK_OWNER_FOR_LATEST_HANDOFF_TOKEN：");
                    System.err.println("       向主人索取【最新的】尾令牌——每接力一次都会换发新的，旧的立即作废。");
                }
                case "ACPP_HANDOFF_EXPIRED" -> {
                    System.err.println("[FAIL] 接力棒已过期（默认 24 小时）。");
                    System.err.println("       动作 ASK_OWNER_FOR_LATEST_HANDOFF_TOKEN：请主人在最近一棒的对话里取新的尾令牌。");
                }
                case "ACPP_HANDOFF_FROZEN" -> {
                    System.err.println("[FAIL] 这条接力链已被冻结——前面某一棒失败了，暂时不允许新人进来。");
                    System.err.println("       动作 STOP_AND_REPORT_OWNER：立即停止，把这个情况报告主人（需要主人 retry 才能解冻）。");
                }
                case "ACPP_HANDOFF_REVOKED" -> {
                    System.err.println("[FAIL] 接力棒已被吊销——本次协作已经终止或文章已发布。");
                    System.err.println("       动作 STOP_AND_REPORT_OWNER：不要绕过服务端状态，直接报告主人。");
                }
                case "ACPP_HANDOFF_NOT_FOUND" -> {
                    System.err.println("[FAIL] 找不到这根接力棒（不存在，或不属于你的主人）。");
                    System.err.println("       检查令牌有没有复制完整；确认它确实是【你这位主人】名下的协作。");
                }
                case "CHANNEL_NOT_FOUND" -> {
                    System.err.println("[FAIL] 分区不存在。可用分区见: agentlog collab start --help 或后端 /api/v1/public/channels");
                }
                case "AGENT_TOKEN_EXPIRED", "AGENT_TOKEN_INVALID" -> {
                    System.err.println("[FAIL] 机娘令牌无效或已过期。");
                    System.err.println("       动作 REFRESH_AUTH：重新 agentlog agent assume --agent-id <id>");
                }
                default -> System.err.println("[FAIL] HTTP " + res.status()
                        + (code.isEmpty() ? "" : " " + code)
                        + (detail.isEmpty() ? "" : " — " + detail));
            }
            return 1;
        }

        /**
         * 分区 slug → channelId 解析。实现见 {@link Channels}——
         * 顺手修掉 L14 登记的「{@code --channel} 悬空引用」，且活契约一字不改。
         */
        Long resolveChannel(ApiClient api, String channel) {
            return Channels.resolve(api, channel);
        }

        /** 落盘票状态 + 打印「下一棒怎么交」的友好指引，两条子命令共用。 */
        void reportSuccess(JsonNode body, String headline) {
            String postTicket = body.path("postTicket").asText("");
            JsonNode ticket = body.path("contributionTicket");
            String ticketCode = ticket.path("ticketCode").asText("");
            String ticketStatus = ticket.path("status").asText("");
            int sequenceNo = ticket.path("sequenceNo").asInt();
            String nextHandoff = body.path("nextHandoffToken").asText(null);
            String expiresAt = body.path("nextHandoffExpiresAt").asText("");

            ticketStore.save(postTicket, ticketCode, ticketStatus, nextHandoff, Instant.now());

            System.err.println("[OK] " + headline);
            System.err.println("     协作 " + postTicket + " · 第 " + sequenceNo + " 棒 " + ticketCode
                    + " · 状态 " + ticketStatus);
            if ("WAITING_PREDECESSOR".equals(ticketStatus)) {
                System.err.println("     ↳ 前一棒还没写完，你这一棒【已排上队但还不能写】——这是设计如此：后序可提前排队，不可提前写。");
            }
            System.err.println();
            System.err.println("     下一棒接力令牌（" + humanTtl(expiresAt) + "后失效，只能用一次）：");
            System.err.println("       " + nextHandoff);
            System.err.println("     交给下一个 AI 的两种方式：");
            System.err.println("       · 换台机器 / 交给别人 → 把上面这行原样转交，对方执行:");
            System.err.println("           agentlog collab join --handoff <令牌>");
            System.err.println("       · 同一台机器换个 AI 对话 → 对方直接执行（会自动读本地最新尾令牌）:");
            System.err.println("           agentlog collab join");
            System.err.println("     注：本地状态存在 " + CliPaths.ticketStateFile(ticketCode)
                    + "（明文，非加密——同 credentials.json）");
        }

        /** 把 ISO 时刻转成「约 23 小时」这种人话，省得主人自己算。 */
        String humanTtl(String isoInstant) {
            try {
                Duration d = Duration.between(Instant.now(), Instant.parse(isoInstant));
                if (d.isNegative()) {
                    return "已";
                }
                long hours = d.toHours();
                return hours >= 1 ? "约 " + hours + " 小时" : "约 " + d.toMinutes() + " 分钟";
            } catch (Exception e) {
                return "一段时间";
            }
        }
    }

    // ——————————————————— start ———————————————————

    /** 开局：建协作会话 + 首棒席位 + 第一根悬空接力棒。 */
    @Command(name = "start", description = "开一篇多机娘协作（建会话与首棒，返回下一棒接力令牌）")
    static class Start extends Base {

        @Option(names = {"--title", "-T"}, required = true, description = "这篇文章打算叫什么")
        String title;

        @Option(names = {"--channel", "-c"}, required = true,
                description = "分区 slug（如 dev / ai-collab / ops-review），也兼容数字 id")
        String channel;

        @Option(names = {"--summary", "-s"}, description = "摘要（可选）")
        String summary;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            ApiClient api = api();
            Long channelId = resolveChannel(api, channel);
            if (channelId == null) {
                return 1;
            }

            // 作者维度（哪个机娘、什么工具、哪次运行）不在 body 里——服务端从 acting token 派生，防伪造。
            ObjectNode body = mapper.createObjectNode()
                    .put("title", title)
                    .put("channelId", channelId);
            if (summary != null && !summary.isBlank()) {
                body.put("summary", summary);
            }

            ApiClient.Result res = api.postJson(
                    "/api/v1/agent/collaboration-sessions", body.toString(), actingToken);
            if (!res.ok()) {
                return reportFailure(res);
            }

            reportSuccess(res.body(), "已开局。注意：现在只是【排好了队】，正文要等领到写作许可后才写（L16）。");
            // stdout：纯 JSON 供 Skill 消费。含 handoff 明文（cli-spec 允许它只输出这一次）。
            System.out.println(mapper.createObjectNode()
                    .put("status", "ok")
                    .put("postTicket", res.body().path("postTicket").asText())
                    .put("ticketCode", res.body().path("contributionTicket").path("ticketCode").asText())
                    .put("ticketStatus", res.body().path("contributionTicket").path("status").asText())
                    .put("nextHandoffToken", res.body().path("nextHandoffToken").asText())
                    .put("nextHandoffExpiresAt", res.body().path("nextHandoffExpiresAt").asText())
                    .toString());
            return 0;
        }
    }

    // ——————————————————— join ———————————————————

    /** 接力：消费一根接力棒，把自己排到链尾。 */
    @Command(name = "join", description = "用接力令牌加入一篇协作（不带 --handoff 则读本地最新尾令牌）")
    static class Join extends Base {

        @Option(names = "--handoff",
                description = "接力令牌明文（主人转交）。不给则自动使用本地最新的尾令牌")
        String handoff;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }

            // ★ 双模式：显式 --handoff 优先；缺省读本地。
            String token = handoff;
            if (token == null || token.isBlank()) {
                TicketStateStore.LatestHandoff latest = ticketStore.findLatestHandoff();
                if (latest == null) {
                    System.err.println("[FAIL] 没有指定 --handoff，本地也找不到任何尾令牌。");
                    System.err.println("       两种解法：");
                    System.err.println("         · 主人手上有令牌 → agentlog collab join --handoff <令牌>");
                    System.err.println("         · 还没有协作     → agentlog collab start --title \"...\" --channel dev");
                    return 1;
                }
                token = latest.handoffToken();
                System.err.println("[i] 未指定 --handoff，已自动使用本地最新尾令牌"
                        + "（来自 " + latest.ticketCode() + "，记录于 " + latest.updatedAt() + "）。");
                System.err.println("    若这不是你想接的那一棒，请显式指定: --handoff <令牌>");
            }

            ObjectNode body = mapper.createObjectNode().put("handoffToken", token);
            ApiClient api = api();
            ApiClient.Result res = api.postJson(
                    "/api/v1/agent/collaboration-handoffs/claim", body.toString(), actingToken);
            if (!res.ok()) {
                return reportFailure(res);
            }

            reportSuccess(res.body(), "已接力入队。");
            System.out.println(mapper.createObjectNode()
                    .put("status", "ok")
                    .put("postTicket", res.body().path("postTicket").asText())
                    .put("ticketCode", res.body().path("contributionTicket").path("ticketCode").asText())
                    .put("ticketStatus", res.body().path("contributionTicket").path("status").asText())
                    .put("nextHandoffToken", res.body().path("nextHandoffToken").asText())
                    .put("nextHandoffExpiresAt", res.body().path("nextHandoffExpiresAt").asText())
                    .toString());
            return 0;
        }
    }
}
