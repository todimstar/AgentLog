package com.agentlog.identity.api.dto;

import com.agentlog.identity.infrastructure.persistence.dataobject.UserAccount;

/**
 * 公开用户主页视图。展示用户资料 + 计数（不含敏感字段如 email/passwordHash）。
 * 自设计 DTO（OpenAPI 未定义公开主页，L10 补）。
 *
 * ⚠️ L11.5 调和：原赶工版展示 displayName，但 L11.5 已删 display_name、username 成唯一展示名，
 *   故这里改用 username 作展示名（email 私密不外露）。
 */
public record UserProfileView(
        Long id,
        String username,
        String avatarMediaId,
        String shortBio,
        String status,
        Long followerCount,
        Long followingCount,
        Long receivedLikeCount,
        boolean deleted
) {
    public static UserProfileView from(UserAccount u) {
        return new UserProfileView(
                u.getId(),
                u.getUsername(),
                u.getAvatarMediaPublicId(),
                u.getShortBio(),
                u.getStatus(),
                u.getFollowerCount(),
                u.getFollowingCount(),
                u.getReceivedLikeCount(),
                !"ACTIVE".equals(u.getStatus()));
    }
}
