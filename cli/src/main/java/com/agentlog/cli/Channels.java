package com.agentlog.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 分区 slug → channelId 解析（L15 顺手修掉 L14 登记的「{@code --channel} 悬空引用」）。
 *
 * <p>★ 问题是什么：L14 的 {@code submit --channel} 强制要一个数字 {@code channelId}，
 * 但<b>整个 CLI 没有任何命令能告诉你这个 id 是几</b>——主人得去翻浏览器、curl 接口或翻 SQL；
 * 机娘自动投稿时更是无从知晓。命令要求一个它自己给不出的值，就是「悬空引用」。
 *
 * <p>★ 为什么改成收 slug：{@code id} 是实现细节（自增主键），{@code slug} 是语义标识
 * （{@code dev} / {@code ai-collab} / {@code ops-review}）。把实现细节泄漏到用户界面，
 * 用户就得反过来去理解你的数据库。同 {@code ticketCode} 用随机码而非自增的取舍方向一致：
 * <b>对外的标识应当稳定、可读、与内部存储解耦。</b>
 *
 * <p>★ 为什么解析放在 CLI 侧而不是让服务端收 slug：调的是公开的 {@code /api/v1/public/channels}
 * （无需认证），于是<b>活契约一字不改</b>、无需重生成前端客户端。改契约是冻结面动作，
 * 能不动就不动——这条修复的收益完全不值得付那个代价。
 *
 * <p>兼容纯数字输入，老用法不破。
 */
public final class Channels {

    private Channels() {
    }

    /**
     * 解析分区标识。
     *
     * @param channel slug（如 {@code dev}）或纯数字 id（兼容旧用法）
     * @return channelId；解析失败返回 null（已在 stderr 打印可用分区清单）
     */
    public static Long resolve(ApiClient api, String channel) {
        if (channel == null || channel.isBlank()) {
            System.err.println("[FAIL] 未指定分区。");
            return null;
        }
        if (channel.matches("\\d+")) {
            return Long.parseLong(channel);
        }

        ApiClient.Result res = api.getJson("/api/v1/public/channels", null);
        if (!res.ok() || !res.body().isArray()) {
            System.err.println("[FAIL] 无法获取分区列表（HTTP " + res.status() + "）。后端起了吗？");
            return null;
        }

        List<String> available = new ArrayList<>();
        for (JsonNode n : res.body()) {
            String slug = n.path("slug").asText("");
            available.add(slug + "（" + n.path("name").asText("") + "）");
            if (slug.equalsIgnoreCase(channel)) {
                return n.path("id").asLong();
            }
        }
        System.err.println("[FAIL] 没有 slug 为 \"" + channel + "\" 的分区。可用分区：");
        available.forEach(s -> System.err.println("       - " + s));
        return null;
    }
}
