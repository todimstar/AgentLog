package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 协作票的本地状态存取（L15）。文件形状对齐 Pack {@code 07-cli/schemas/ticket-state.schema.json}：
 * <pre>
 * ~/.agentlog/state/tickets/CT-xxxx.json
 * {
 *   "postTicket":       "PT-...",
 *   "ticketCode":       "CT-...",
 *   "ticketStatus":     "WAITING_PREDECESSOR",
 *   "nextHandoffToken": "handoff_...",   // 下一棒的尾令牌（明文）
 *   "leaseToken":       null,            // L16 领租约时才有
 *   "updatedAt":        "2026-07-31T..."
 * }
 * </pre>
 *
 * <p>★ <b>为什么要落盘</b>（主人 2026-07-30 拍板「两者都支持，且提示要友好」）：
 * 蓝图两处说法不完全一致——{@code SKILL.md} 说「立即把下一棒 HandoffToken 返回给主人」，
 * 而 {@code ticket-state.schema.json} 又定义了 {@code nextHandoffToken} 字段说明它要存本地。
 * 两者其实各管一个场景：
 * <ul>
 *   <li><b>跨机 / 交给别人</b>：主人手工转交明文（唯一可行的方式）；</li>
 *   <li><b>同机换个 AI 对话</b>：一台机器一份 CLI、一个 {@code ~/.agentlog}，
 *       下一个 AI 直接读本地最新尾令牌即可，不必让主人当人肉剪贴板。</li>
 * </ul>
 * 所以 {@code collab join} 两种都支持：{@code --handoff} 显式优先，缺省读本地最新。
 *
 * <p>⚠️ 明文存盘，非加密——见 {@link CliPaths#ticketStateDir()} 的说明。
 */
public class TicketStateStore {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 写入/覆盖一张票的本地状态。 */
    public void save(String postTicket, String ticketCode, String ticketStatus,
                     String nextHandoffToken, Instant updatedAt) {
        ObjectNode root = mapper.createObjectNode()
                .put("postTicket", postTicket)
                .put("ticketCode", ticketCode)
                .put("ticketStatus", ticketStatus)
                .put("updatedAt", updatedAt.toString());
        // schema 里 nextHandoffToken 与 leaseToken 都是 string|null，显式写 null 比省略更贴合契约。
        if (nextHandoffToken == null) {
            root.putNull("nextHandoffToken");
        } else {
            root.put("nextHandoffToken", nextHandoffToken);
        }
        root.putNull("leaseToken");   // L16 领租约时才填

        Path file = CliPaths.ticketStateFile(ticketCode);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            tightenPermissions(file);
        } catch (IOException e) {
            throw new IllegalStateException("写入票状态失败: " + file + " — " + e.getMessage(), e);
        }
    }

    /**
     * 取「本地最新的那根悬空尾令牌」——按 updatedAt 倒序找第一条带 nextHandoffToken 的票。
     *
     * <p>为什么按 updatedAt 而不是文件修改时间：文件时间会被复制/同步工具改写，
     * 而 updatedAt 是我们自己写进去的业务时间，可信得多。
     *
     * @return 找到则返回 [ticketCode, handoffToken]；没有则 null
     */
    public LatestHandoff findLatestHandoff() {
        Path dir = CliPaths.ticketStateDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        List<ObjectNode> states = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    JsonNode n = mapper.readTree(Files.readString(p));
                    if (n.isObject() && n.hasNonNull("nextHandoffToken")) {
                        states.add((ObjectNode) n);
                    }
                } catch (Exception ignored) {
                    // 单个文件损坏不该让整条命令失败——跳过它，继续找别的。
                }
            }
        } catch (IOException e) {
            return null;
        }
        return states.stream()
                .max(Comparator.comparing(n -> n.path("updatedAt").asText("")))
                .map(n -> new LatestHandoff(
                        n.path("ticketCode").asText(""),
                        n.path("nextHandoffToken").asText(),
                        n.path("updatedAt").asText("")))
                .orElse(null);
    }

    /** 尽力收紧权限为仅本人可读写；Windows 等不支持 POSIX 的平台优雅跳过（同 CredentialStore）。 */
    private void tightenPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // 学习项目从简；生产应改用 ACL 收紧。
        }
    }

    /** 本地找到的最新尾令牌：来自哪张票、令牌明文、那张票的更新时间。 */
    public record LatestHandoff(String ticketCode, String handoffToken, String updatedAt) {
    }
}
