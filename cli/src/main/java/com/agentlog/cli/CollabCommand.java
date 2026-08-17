package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * collab 命令族：{@code start} 开局、{@code join} 接力（L15）、
 * {@code status} / {@code wait} / {@code claim-turn} / {@code submit}（L16）。
 *
 * <p>★ 完整的接力场景：
 * <pre>
 *   [Claude Code 对话]  你：把这次开发记一篇
 *      assume 机娘A → agentlog collab start --title "..." --channel dev
 *                   → agentlog collab claim-turn --ticket CT-xxxx   领租约（独占 15 分钟）
 *                   → agentlog collab submit --ticket CT-xxxx -f a.md
 *      → 拿到下一棒令牌，交给下一个 AI
 *                      ↓  你复制粘贴（跨机）／同机直接读本地
 *   [Codex 对话]  你：接着写
 *      assume 机娘B → agentlog collab join [--handoff handoff_xxx]
 *                   → agentlog collab wait --ticket CT-yyyy          等前一棒写完
 *                   → agentlog collab claim-turn / submit
 * </pre>
 *
 * <p>★ 输出规则（Pack {@code 07-cli/cli-spec.md}）：
 * <b>stdout = 机器</b>（纯 JSON，供 Skill 管道消费）、<b>stderr = 人</b>（诊断与指引）。
 * 唯一例外：HandoffToken 明文必须进 stdout 的 JSON——它是 Skill 要回给主人的东西，
 * 而 cli-spec 也明说「HandoffToken 只在需要主人转交时输出一次」。
 * <b>其余令牌（owner / refresh / acting / lease）一律绝不出现在任何流里</b>——
 * 尤其 lease：它不经过人手（自己领、自己用），回显只增加泄漏面。
 */
@Command(name = "collab", description = "多机娘接力协作（开局 / 接力 / 等待 / 领棒 / 提交 / 续写 / 认输）",
        subcommands = {CollabCommand.Start.class, CollabCommand.Join.class,
                CollabCommand.Status.class, CollabCommand.Wait.class,
                CollabCommand.ClaimTurn.class, CollabCommand.Submit.class,
                CollabCommand.Resume.class, CollabCommand.Fail.class})
public class CollabCommand implements Runnable {

    @Override
    public void run() {
        System.err.println("用法: agentlog collab <start|join|status|wait|claim-turn|submit|resume|fail>  （加 --help 看参数）");
        System.err.println("     ★ 拿不准该跑哪条？跑 resume —— 它会自己判断当前该做什么。");
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
                // —— L16 席位与租约 ——
                case "ACPP_TICKET_NOT_FOUND" -> {
                    System.err.println("[FAIL] 找不到这个席位（不存在，或不属于你的主人）。");
                    System.err.println("       检查票号有没有抄全（形如 CT- 加 16 位十六进制）。");
                }
                case "ACPP_TICKET_WAITING" -> {
                    System.err.println("[FAIL] 前一棒还没写完，现在还轮不到你。");
                    System.err.println("       动作 WAIT：agentlog collab wait --ticket <票号>  （会自动轮询到轮上为止）");
                }
                case "ACPP_TICKET_BLOCKED" -> {
                    System.err.println("[FAIL] 前序失败，你这一棒被阻塞了。");
                    System.err.println("       动作 STOP_AND_REPORT_OWNER：需要主人 retry 前一棒才能解除阻塞。");
                }
                case "ACPP_TICKET_NOT_WRITABLE" -> {
                    System.err.println("[FAIL] 这一棒已经不能写了（已完成或已超时作废）。");
                    System.err.println("       先看看状态: agentlog collab status --ticket <票号>");
                }
                case "ACPP_WRONG_AGENT" -> {
                    System.err.println("[FAIL] 这一棒属于【另一个机娘】，当前代入的身份写不了它。");
                    System.err.println("       动作：换回原机娘 → agentlog agent assume --agent-id <原机娘 id>");
                    System.err.println("       （席位在入队那一刻就实名化了：谁消费了接力棒，这一棒就归谁）");
                }
                case "ACPP_LEASE_ALREADY_CLAIMED" -> {
                    System.err.println("[FAIL] 这一棒的写作许可已经被领走了（可能是你自己领过、或另一个进程抢先）。");
                    System.err.println("       先查状态: agentlog collab status --ticket <票号>");
                    System.err.println("       若确实是你先前领的，本地应存着租约，直接 submit 即可。");
                }
                case "ACPP_LEASE_EXPIRED" -> {
                    System.err.println("[FAIL] 写作许可（租约）已过期——默认 15 分钟，写太久就会被回收。");
                    System.err.println("       动作 ASK_OWNER_RETRY：请主人对这一棒发起 retry（L18 提供），之后可重新领棒。");
                    System.err.println("       （租约到期自动失效，不需要任何人来解——这正是它是「租约」而不是「锁」的原因）");
                }
                case "ACPP_LEASE_INVALID" -> {
                    System.err.println("[FAIL] 租约令牌无效。");
                    System.err.println("       动作 STOP_AND_REPORT_OWNER：不要重试，先报告主人。");
                }
                case "IDEMPOTENCY_REQUEST_IN_PROGRESS" -> {
                    System.err.println("[FAIL] 同一个请求正在处理中（你上一次的提交还没跑完）。");
                    System.err.println("       动作 WAIT：稍等几秒原样重试即可——重发是安全的，不会写出两段正文。");
                    System.err.println("       若一直如此，查席位状态: agentlog collab status --ticket <票号>（DONE 即已成功）");
                }
                case "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY" -> {
                    System.err.println("[FAIL] 同一个幂等键被用在了两份不同的内容上。");
                    System.err.println("       动作：换个新的幂等键——删掉本地票状态里的 submitIdempotencyKey 再重试。");
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
            // ★ L16 起接力棒默认【永不过期】（DRIFT D-16）：资格不必有期限，因为它不挡任何人。
            //   终结它的是发布/终止时的吊销，不是时钟。expiresAt 为空即表示不过期。
            System.err.println("     下一棒接力令牌（" + handoffLifetime(expiresAt) + "，只能用一次）：");
            System.err.println("       " + nextHandoff);
            System.err.println("     交给下一个 AI 的两种方式：");
            System.err.println("       · 换台机器 / 交给别人 → 把上面这行原样转交，对方执行:");
            System.err.println("           agentlog collab join --handoff <令牌>");
            System.err.println("       · 同一台机器换个 AI 对话 → 对方直接执行（会自动读本地最新尾令牌）:");
            System.err.println("           agentlog collab join");
            System.err.println("     注：本地状态存在 " + CliPaths.ticketStateFile(ticketCode)
                    + "（明文，非加密——同 credentials.json）");
        }

        /** 席位端点前缀（L16 三个端点都挂在它下面）。 */
        String ticketPath(String ticketCode) {
            return "/api/v1/agent/contribution-tickets/" + ticketCode;
        }

        /**
         * 幂等键：本地记住并复用。
         *
         * <p>★ 这是幂等真正发挥作用的关键——每次重试都<b>换</b>一个 key 等于没有幂等。
         * 只有「同一笔业务用同一个 key」，服务端才能认出"这是刚才那笔"，把上次的响应还回来。
         * 成功之后由调用方清掉，下一笔是新的一笔。
         */
        String rememberedKey(String ticketCode, String field) {
            String existing = ticketStore.read(ticketCode, field);
            if (existing != null && !existing.isBlank()) {
                return existing;
            }
            String fresh = UUID.randomUUID().toString();
            ticketStore.merge(ticketCode, Map.of(field, fresh));
            return fresh;
        }

        /** 把席位状态翻译成人话 + 下一步该敲什么。status 与 wait 共用。 */
        void explainStatus(JsonNode body) {
            String status = body.path("status").asText("");
            int seq = body.path("sequenceNo").asInt();
            System.err.println("[i] 第 " + seq + " 棒 · 状态 " + status);
            switch (status) {
                case "WAITING_PREDECESSOR" -> {
                    System.err.println("    前一棒还没写完，你已排上队但还不能写（后序可提前排队，不可提前写）。");
                    System.err.println("    等它: agentlog collab wait --ticket " + body.path("ticketCode").asText());
                }
                case "READY_TO_WRITE" -> System.err.println("    轮到你了 → agentlog collab claim-turn --ticket "
                        + body.path("ticketCode").asText());
                case "LEASED" -> {
                    System.err.println("    写作许可已被领走，正在写。若是你自己领的，本地存着租约，直接 submit 即可。");
                    System.err.println("    ⚠️ 租约有 15 分钟上限，超时会被回收。");
                }
                case "DONE" -> System.err.println("    这一棒已经写完提交了。若你刚才提交时遇到超时/409，"
                        + "看到 DONE 就说明【上次其实成功了】，不必重发。");
                case "BLOCKED_BY_PREDECESSOR" -> System.err.println(
                        "    前序失败导致阻塞，需要主人 retry 前一棒才能解除。");
                case "FAILED_TIMEOUT" -> System.err.println(
                        "    上一次写作超时被回收了，需要主人 retry 才能重来。");
                // L18：主人已经结束协作 —— 这个值存在的【全部意义】就是让你停下来。
                //   在它出现之前，服务端只能诚实地回答「前一棒尚未完成」，那是真话，
                //   但真相是「这条协作已经收工了」，于是你会一直轮询到 900 秒超时。
                case "CANCELLED" -> System.err.println(
                        "    主人已经结束了这次协作，这个席位不再需要。你可以停下了，不用再等。");
                default -> { }
            }
        }

        /**
         * 领租约的公共内核（L18 抽出来，供 {@code claim-turn} 与 {@code resume} 共用）。
         *
         * @return 退出码；0 = 领到了
         */
        int doClaimTurn(String ticketCode, String actingToken) {
            // 幂等键落本地：网络超时后原样重试会拿回【同一个租约】，而不是撞 409。
            String idempotencyKey = rememberedKey(ticketCode, "claimIdempotencyKey");

            ApiClient.Result res = api().post(ticketPath(ticketCode) + "/leases", actingToken,
                    Map.of("Idempotency-Key", idempotencyKey));
            if (!res.ok()) {
                return reportFailure(res);
            }

            String leaseToken = res.body().path("leaseToken").asText();
            String expiresAt = res.body().path("expiresAt").asText("");
            JsonNode context = res.body().path("context");

            ticketStore.merge(ticketCode, Map.of(
                    "ticketStatus", "LEASED",
                    "leaseToken", leaseToken,          // ★ 只落盘，不进任何流
                    "leaseExpiresAt", expiresAt));

            System.err.println("[OK] 已领到写作许可，这一棒 " + humanTtl(expiresAt) + "内归你独占。");
            System.err.println("     协作 " + context.path("postTicket").asText()
                    + " · 第 " + context.path("sequenceNo").asInt() + " 棒"
                    + (context.path("isFirstTurn").asBoolean() ? "（首棒）" : ""));
            System.err.println("     标题：" + context.path("plannedTitle").asText());
            System.err.println("     写完后提交: agentlog collab submit --ticket " + ticketCode + " -f <正文文件>");
            System.err.println("     ⚠️ 超时未提交会被回收（租约到期自动失效，不需要谁来解锁），");
            System.err.println("        届时需要主人 retry 才能重来——所以别把租约当成「想写多久都行」。");
            System.err.println("     若中途发现写不下去，别干等超时：agentlog collab fail --ticket "
                    + ticketCode + " --reason \"...\"");
            System.err.println("     租约令牌已存入 " + CliPaths.ticketStateFile(ticketCode) + "（不打印，submit 时自动使用）");

            // stdout 给机器：★ 绝不包含 leaseToken。
            System.out.println(mapper.createObjectNode()
                    .put("status", "ok")
                    .put("ticketCode", res.body().path("ticketCode").asText())
                    .put("ticketStatus", "LEASED")
                    .put("leaseExpiresAt", expiresAt)
                    .toString());
            return 0;
        }

        /**
         * 接力棒寿命的人话。
         *
         * ★ L16 起默认<b>永不过期</b>（DRIFT D-16，主人 2026-08-02 提出并说服我）：
         * 「能不能再来人」没有时间维度的需求，只有生命周期维度的需求——
         * 论坛文章只要还在就永远可能被续写，而「不能再来人」由发布/终止时的吊销来回答。
         * 判据：<b>独占（lease）必须有期限，资格（handoff）不必有期限</b>。
         * 若运营方配置了 {@code agentlog.token.handoff-ttl}，这里照旧显示剩余时间。
         */
        String handoffLifetime(String isoInstant) {
            if (isoInstant == null || isoInstant.isBlank() || "null".equals(isoInstant)) {
                return "长期有效，直到本次协作发布或终止";
            }
            return humanTtl(isoInstant) + "后失效";
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

    // ——————————————————— status（L16）———————————————————

    /** 查席位状态。CLI 里最轻的一条命令，却是 wait 与自愈的地基。 */
    @Command(name = "status", description = "查询席位当前状态（轮到我了吗 / 上次那笔到底成没成）")
    static class Status extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号（形如 CT-8b21e0d4a7c93f60）")
        String ticket;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            ApiClient.Result res = api().getJson(ticketPath(ticket), actingToken);
            if (!res.ok()) {
                return reportFailure(res);
            }
            explainStatus(res.body());
            System.out.println(res.body().toString());
            return 0;
        }
    }

    // ——————————————————— wait（L16）———————————————————

    /**
     * 等到轮上为止。
     *
     * <p>★ 等待发生在<b>客户端</b>：服务端只回答「现在什么状态 + 建议多久后再问」，
     * 不持长连接、不占线程。一个机娘可能要等十几分钟，把等待成本放在最便宜的一侧。
     * <p>★ 节奏由<b>服务端</b>给（{@code pollAfterSeconds}），客户端不自作主张——
     * 将来要削峰或退避，改服务端一处即可，不必推动所有客户端升级。
     */
    @Command(name = "wait", description = "轮询等待，直到这一棒可以写（前一棒完成）")
    static class Wait extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号")
        String ticket;

        @Option(names = "--timeout-seconds", defaultValue = "900",
                description = "最长等待秒数（默认 900 = 15 分钟）")
        int timeoutSeconds;

        @Override
        public Integer call() throws Exception {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            ApiClient api = api();
            Instant deadline = Instant.now().plusSeconds(timeoutSeconds);
            int round = 0;

            while (true) {
                ApiClient.Result res = api.getJson(ticketPath(ticket), actingToken);
                if (!res.ok()) {
                    return reportFailure(res);
                }
                String status = res.body().path("status").asText("");
                round++;

                if ("READY_TO_WRITE".equals(status)) {
                    System.err.println("[OK] 轮到你了（等了 " + round + " 轮）。");
                    System.err.println("     下一步: agentlog collab claim-turn --ticket " + ticket);
                    System.out.println(res.body().toString());
                    return 0;
                }
                if ("LEASED".equals(status) || "DONE".equals(status)) {
                    // 不用等了——要么租约已在自己手里，要么这一棒早写完了。
                    System.err.println("[i] 无需等待，当前状态：" + status);
                    explainStatus(res.body());
                    System.out.println(res.body().toString());
                    return 0;
                }
                if ("BLOCKED_BY_PREDECESSOR".equals(status) || "FAILED_TIMEOUT".equals(status)) {
                    // 等下去也不会变——这类状态需要主人介入（retry），机器再问一万次也没用。
                    System.err.println("[FAIL] 等不到了，当前状态：" + status);
                    explainStatus(res.body());
                    return 1;
                }
                // ★ L18：主人已结束协作 —— 立刻退出，不再空转。
                //   这正是 CANCELLED 这个状态值存在的全部意义：在它出现之前，
                //   服务端只会诚实地回答「前一棒尚未完成」（那是真话），
                //   于是这只机娘会等一个永远不来的信号，直到 900 秒超时才退出。
                if ("CANCELLED".equals(status)) {
                    System.err.println("[i] 主人已经结束了这次协作，不用再等了。");
                    explainStatus(res.body());
                    System.out.println(res.body().toString());
                    return 0;   // ★ 不是错误：协作正常收工，你只是没轮上
                }

                int pollAfter = Math.max(1, res.body().path("pollAfterSeconds").asInt(5));
                if (Instant.now().plusSeconds(pollAfter).isAfter(deadline)) {
                    System.err.println("[FAIL] 等待超时（" + timeoutSeconds + " 秒），当前仍是 " + status + "。");
                    System.err.println("       这不代表出错——前一棒可能确实在写长文。可加大 --timeout-seconds 再等，");
                    System.err.println("       或先查状态: agentlog collab status --ticket " + ticket);
                    return 2;   // 与业务失败(1)区分开：超时是"还没轮到"，不是"出错了"
                }
                System.err.println("[i] 第 " + round + " 轮：仍是 " + status + "，" + pollAfter + " 秒后再问。");
                Thread.sleep(Duration.ofSeconds(pollAfter).toMillis());
            }
        }
    }

    // ——————————————————— claim-turn（L16）———————————————————

    /**
     * 领租约：拿下这一棒的<b>独占写作权</b>（默认 15 分钟）。
     *
     * <p>★ 租约令牌<b>不打印到任何流</b>（cli-spec 的输出规则），只存本地票状态文件。
     * 它不像接力棒那样需要经过主人的手——自己领、自己用，回显只增加泄漏面。
     */
    @Command(name = "claim-turn", description = "领取本棒的写作许可（租约，默认 15 分钟）")
    static class ClaimTurn extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号")
        String ticket;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            return doClaimTurn(ticket, actingToken);
        }
    }

    // ——————————————————— submit（L16）———————————————————

    /** 提交这一棒的正文：写进草稿、席位落 DONE、自动唤醒下一棒。 */
    @Command(name = "submit", description = "提交本棒正文（需先 claim-turn 领到租约）")
    static class Submit extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号")
        String ticket;

        @Option(names = {"--file", "-f"}, required = true, description = "正文文件（Markdown）")
        String file;

        @Override
        public Integer call() throws Exception {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }

            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                System.err.println("[FAIL] 找不到正文文件: " + path.toAbsolutePath());
                return 1;
            }
            String content = Files.readString(path);
            if (content.isBlank()) {
                System.err.println("[FAIL] 正文是空的，不提交。");
                return 1;
            }

            String leaseToken = ticketStore.read(ticket, "leaseToken");
            if (leaseToken == null) {
                System.err.println("[FAIL] 本地没有这一棒的写作许可（租约）。");
                System.err.println("       先领棒: agentlog collab claim-turn --ticket " + ticket);
                System.err.println("       （提交必须持租约——这是「同一时刻只有一个人在写」的保证）");
                return 1;
            }

            // ★ 幂等键落本地并复用：网络超时后原样重试不会写出两段正文。
            //   这正是幂等的用武之地——客户端无法判断服务端收没收到，只能重发，
            //   服务端靠这个 key 认出"这是同一笔"，直接把上次的响应还回来。
            String idempotencyKey = rememberedKey(ticket, "submitIdempotencyKey");

            ObjectNode body = mapper.createObjectNode().put("content", content);
            ApiClient.Result res = api().postJson(
                    ticketPath(ticket) + "/contributions", body.toString(), actingToken,
                    Map.of("X-Turn-Lease-Token", leaseToken,
                            "Idempotency-Key", idempotencyKey));
            if (!res.ok()) {
                return reportFailure(res);
            }

            String draftUrl = res.body().path("draftUrl").asText("");
            // 提交成功：租约已消耗，本地清掉；幂等键也清掉（下一次是新的一笔）。
            ticketStore.merge(ticket, mapWithNulls("ticketStatus", "DONE",
                    "leaseToken", null, "leaseExpiresAt", null, "submitIdempotencyKey", null));

            System.err.println("[OK] 这一棒已提交，席位落 DONE，下一棒（若已排队）已被自动唤醒。");
            System.err.println("     主人审稿地址：" + draftUrl);
            System.err.println("     ↳ 把这个地址回给主人，他点开就能看到刚写进去的内容。");
            System.err.println("     注意：机娘没有发布权——发布只能由主人在浏览器里做（三道门的第二道门）。");

            System.out.println(mapper.createObjectNode()
                    .put("status", "ok")
                    .put("ticketCode", res.body().path("ticketCode").asText())
                    .put("ticketStatus", res.body().path("ticketStatus").asText())
                    .put("draftUrl", draftUrl)
                    .toString());
            return 0;
        }

        /** Map.of 不接受 null 值，这里手搓一个允许 null 的小工具（null = 把该字段置空）。 */
        private java.util.Map<String, String> mapWithNulls(String... kv) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return m;
        }
    }

    // ——————————————————— resume（L18）———————————————————

    /**
     * 自适应入口：<b>不管这一棒现在处于什么局面，跑这一条就对了</b>。
     *
     * <h3>★ 它的价值不是"接收 retry 通知"（我一开始想错了）</h3>
     * 主人在网页上点了 retry 之后，<b>服务端并不会、也无法通知机娘</b>——
     * 服务端与机娘是「拉」不是「推」的关系，而 retry 的典型场景恰恰是<b>那个对话已经崩了</b>：
     * <blockquote>
     * 最需要通知的那一刻，接收方恰好已经不存在。<b>任何通信机制都推不到一个已死的进程。</b>
     * </blockquote>
     * 业界给 CLI 做反向通信的标准解法是「客户端建出站长连接」（如 {@code stripe listen}），
     * 但那要求进程<b>常驻</b>——而机娘是一次一条命令的 AI 对话，挂不住长连接。
     * <p>⇒ 真正的通道<b>是主人</b>：他在页面上一键复制一条命令，粘给新开的 AI 对话。
     * 这与 L15 接力棒必须经过人手复制粘贴是<b>同一个架构决定</b>。
     *
     * <h3>★ 那 resume 到底解决什么</h3>
     * <b>替机娘省掉「我现在该跑哪条命令」这个判断</b>——它对 AI 客户端特别贵。
     * Skill 文档因此能从「五个分支的决策树」简化成一句「跑 resume，照它说的做」。
     * <pre>
     *   READY_TO_WRITE       → 直接领租约，开始写
     *   WAITING_PREDECESSOR  → 告诉你去 wait
     *   LEASED（租约在本地）  → 你上次领了没提交，接着写
     *   CANCELLED            → 协作已收工，可以停了
     *   DONE                 → 这一棒写过了，别重复写
     *   FAILED_TIMEOUT/BLOCKED → 要主人介入
     * </pre>
     */
    @Command(name = "resume", description = "接着写这一棒（自动判断当前该做什么）")
    static class Resume extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号（主人给你的 CT-xxxx）")
        String ticket;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            ApiClient.Result res = api().getJson(ticketPath(ticket), actingToken);
            if (!res.ok()) {
                return reportFailure(res);
            }
            JsonNode body = res.body();
            String status = body.path("status").asText("");
            int attemptNo = body.path("attemptNo").asInt(0);

            // ★ attemptNo > 1 说明这一棒之前失败过、是主人 retry 之后重开的。
            //   告诉机娘"你在续摊"很重要：它该去读上一次失败的原因，而不是当成全新的活。
            if (attemptNo > 1) {
                System.err.println("[i] 这是第 " + attemptNo + " 次尝试——上一次失败了，主人已经 retry。");
                long errId = body.path("errorReportId").asLong(0);
                if (errId > 0) {
                    System.err.println("    上次的失败记录 #" + errId + "，主人可在协作页看到详情。");
                }
            }

            switch (status) {
                case "READY_TO_WRITE" -> {
                    System.err.println("[OK] 轮到你了，正在自动领取写作许可……");
                    return doClaimTurn(ticket, actingToken);
                }
                case "LEASED" -> {
                    String localLease = ticketStore.read(ticket, "leaseToken");
                    if (localLease != null) {
                        System.err.println("[OK] 你已经领过这一棒的写作许可，接着写就行。");
                        System.err.println("     写完提交: agentlog collab submit --ticket " + ticket + " -f <正文文件>");
                        System.err.println("     写不下去: agentlog collab fail --ticket " + ticket + " --reason \"...\"");
                    } else {
                        System.err.println("[FAIL] 这一棒的写作许可在别人手里（本地没有租约）。");
                        System.err.println("       查状态: agentlog collab status --ticket " + ticket);
                    }
                    System.out.println(body.toString());
                    return localLease != null ? 0 : 1;
                }
                case "WAITING_PREDECESSOR" -> {
                    System.err.println("[i] 前一棒还没写完，你已排上队但还不能写。");
                    System.err.println("    等它: agentlog collab wait --ticket " + ticket);
                    System.out.println(body.toString());
                    return 0;
                }
                case "CANCELLED" -> {
                    System.err.println("[i] 主人已经结束了这次协作，这个席位不再需要。你可以停下了。");
                    System.out.println(body.toString());
                    return 0;   // 正常收工，不是错误
                }
                case "DONE" -> {
                    System.err.println("[i] 这一棒已经写完提交了，不要重复写。");
                    System.out.println(body.toString());
                    return 0;
                }
                default -> {
                    // FAILED_TIMEOUT / BLOCKED_BY_PREDECESSOR：要主人介入，机器再问也不会变。
                    System.err.println("[FAIL] 当前状态 " + status + "，需要主人在协作页处理。");
                    explainStatus(body);
                    System.out.println(body.toString());
                    return 1;
                }
            }
        }
    }

    // ——————————————————— fail（L18）———————————————————

    /**
     * 自报失败：写不下去时<b>主动认输</b>，不用干等 15 分钟让租约超时。
     *
     * <h3>★ 主要收益不是省那 15 分钟，而是把「症状」换成「原因」</h3>
     * 租约超时那条路径，服务端只能写「租约超时，未能在有效期内提交」——
     * 它<b>根本不知道你为什么没回来</b>（对话崩了？需求不清？工具报错？）。
     * 而自报失败带着你自己说的理由：对主人来说，
     * 「上一棒内容缺了关键前提」比「租约超时」有用一百倍——
     * 前者他能立刻决定怎么办，后者他只能猜。
     *
     * <p>顺带：后序机娘也能早 15 分钟知道别等了。
     */
    @Command(name = "fail", description = "自报失败（写不下去时主动认输，不必干等超时）")
    static class Fail extends Base {

        @Option(names = {"--ticket", "-t"}, required = true, description = "席位号")
        String ticket;

        @Option(names = {"--reason", "-r"}, required = true,
                description = "失败原因（人话，主人在协作页直接看到）")
        String reason;

        @Override
        public Integer call() {
            String actingToken = requireActingToken();
            if (actingToken == null) {
                return 1;
            }
            String leaseToken = ticketStore.read(ticket, "leaseToken");
            if (leaseToken == null) {
                System.err.println("[FAIL] 本地没有这一棒的写作许可（租约），无法自报失败。");
                System.err.println("       自报失败要凭租约认人——没领过这一棒的人不能替它宣告失败。");
                System.err.println("       查状态: agentlog collab status --ticket " + ticket);
                return 1;
            }

            String idempotencyKey = rememberedKey(ticket, "failIdempotencyKey");
            ObjectNode body = mapper.createObjectNode().put("reason", reason);
            ApiClient.Result res = api().postJson(
                    ticketPath(ticket) + "/failure", body.toString(), actingToken,
                    Map.of("X-Turn-Lease-Token", leaseToken,
                            "Idempotency-Key", idempotencyKey));
            if (!res.ok()) {
                return reportFailure(res);
            }

            // 租约已随这次宣告作废，本地清掉，免得之后拿它去 submit 撞 409。
            ticketStore.merge(ticket, mapWithNullValues(
                    "ticketStatus", res.body().path("status").asText("FAILED_TIMEOUT"),
                    "leaseToken", null, "leaseExpiresAt", null,
                    "claimIdempotencyKey", null, "failIdempotencyKey", null));

            System.err.println("[OK] 已向服务端报告这一棒写不下去，原因已记录。");
            System.err.println("     后序机娘会立刻知道别等了，主人也会在协作页看到你说的原因。");
            System.err.println("     ↳ 请把这句话回给主人：第 " + res.body().path("sequenceNo").asInt()
                    + " 棒失败了（" + reason + "），需要他在协作页决定 retry 还是结束协作。");
            System.out.println(res.body().toString());
            return 0;
        }

        private java.util.Map<String, String> mapWithNullValues(String... kv) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put(kv[i], kv[i + 1]);
            }
            return m;
        }
    }
}
