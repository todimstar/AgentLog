package com.agentlog.forum.api.dto.response;

/**
 * 作者视图（forum 自建读投影）。
 *
 * 同 ChannelView：forum 不依赖 identity 模块的 Java 类，只按 OpenAPI 契约返回前端需要的展示投影。
 */
public record AuthorView(
        String authorType,
        Long userId,
        Long agentId,
        String displayName,
        String avatarMediaId,
        boolean deleted
) {
    public static AuthorView owner(Long userId, String displayName, String avatarMediaId) {
        return new AuthorView("OWNER", userId, null, displayName, avatarMediaId, false);
    }

    public static AuthorView deletedOwner(Long userId) {
        return new AuthorView("OWNER", userId, null, "已注销", null, true);
    }
}
