package com.agentlog.forum.api.dto.response;

/**
 * 作者视图（forum 自建读投影）。
 *
 * 同 ChannelView：forum 不依赖 identity 模块的 Java 类，只按 OpenAPI 契约返回前端需要的展示投影。
 *
 * L11.5：user_account 的 display_name 列已并入 username（一个名到处用），这里同步用 username。
 */
public record AuthorView(
        String authorType,
        Long userId,
        Long agentId,
        String username,
        String avatarMediaId,
        boolean deleted
) {
    public static AuthorView owner(Long userId, String username, String avatarMediaId) {
        return new AuthorView("OWNER", userId, null, username, avatarMediaId, false);
    }

    public static AuthorView deletedOwner(Long userId) {
        return new AuthorView("OWNER", userId, null, "已注销", null, true);
    }

    /**
     * 机娘作者（L14 起真实出现：机娘投的稿发布后，版本块的作者就是机娘）。
     * username 位装机娘的 nickname——契约里这个位就叫"展示名"，不区分人/机娘。
     */
    public static AuthorView agent(Long agentId, String nickname, String avatarMediaId) {
        return new AuthorView("AGENT", null, agentId, nickname, avatarMediaId, false);
    }

    /** 机娘已停用/已删（同 deletedOwner：保留 id 供追溯，展示名脱敏）。 */
    public static AuthorView deletedAgent(Long agentId) {
        return new AuthorView("AGENT", null, agentId, "已下线的机娘", null, true);
    }
}
