package com.agentlog.identity;

/**
 * 用户视图（响应体）。字段对齐 OpenAPI UserView。
 * 绝不包含 passwordHash —— 响应永远不暴露密码相关字段。
 */
public record UserView(
        Long id,
        String username,
        String displayName,
        String avatarMediaId,
        String shortBio) {

    static UserView from(UserAccount account) {
        return new UserView(
                account.getId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getAvatarMediaPublicId(),
                account.getShortBio());
    }
}
