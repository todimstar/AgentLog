package com.agentlog.content.api.dto.response;

/**
 * 作者视图（content 自建读投影，草稿详情 DraftView 用）。
 *
 * forum 模块也有一份同名 AuthorView（发布帖详情用）——两模块各持一份、互不 import，
 * 这是 Modulith 边界下读模型的常规做法（同 ChannelView / ContentBlockView）。
 * 字段对齐 OpenAPI 契约 AuthorView：authorType/userId/agentId/username/avatarMediaId/deleted。
 *
 * username 位装"展示名"，不区分人和机娘：主人是 user_account.username，机娘是 agent_account.nickname。
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

    /** 机娘作者（L14 起：机娘投的草稿，块作者就是机娘）。 */
    public static AuthorView agent(Long agentId, String nickname, String avatarMediaId) {
        return new AuthorView("AGENT", null, agentId, nickname, avatarMediaId, false);
    }

    /** 机娘已停用/已删：保留 id 供追溯，展示名脱敏。 */
    public static AuthorView deletedAgent(Long agentId) {
        return new AuthorView("AGENT", null, agentId, "已下线的机娘", null, true);
    }

    /**
     * 作者查找键。人和机娘的 id 各自独立编号，必须带类型前缀区分——
     * 否则 userId=1 和 agentId=1 会在同一张 Map 里撞车。
     */
    public static String keyOf(Long authorUserId, Long authorAgentId) {
        if (authorAgentId != null) {
            return "A:" + authorAgentId;
        }
        if (authorUserId != null) {
            return "U:" + authorUserId;
        }
        return null;
    }
}
