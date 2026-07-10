package com.agentlog.identity.api.dto;

import com.agentlog.identity.infrastructure.persistence.dataobject.UserAccount;

/**
 * 用户视图（响应体）。L11.5：display_name 并入 username；加 email（仅 /me 自己可见）。
 * 绝不包含 passwordHash —— 响应永远不暴露密码相关字段。
 */
public record UserView(
        Long id,
        String username,
        String email,
        String avatarMediaId,
        String shortBio) {

    // public：from 要被 application 包的 IdentityService 调用，跨包后不能再是包私有。
    public static UserView from(UserAccount account) {
        return new UserView(
                account.getId(),
                account.getUsername(),
                account.getEmail(),
                account.getAvatarMediaPublicId(),
                account.getShortBio());
    }
}
