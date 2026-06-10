package com.agentlog.shared.security;

import com.agentlog.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户 id 的统一入口。
 *
 * 登录后 Spring Security 把 principal 的 name 设为数据库用户 id（见 AppUserDetailsService），
 * 这里解析回 long。后续所有 owner 域的行级授权（owner_user_id = currentUserId）都从这里取身份，
 * 杜绝"接受客户端传入的 owner id"这种越权风险。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 返回当前登录用户 id；未登录则抛 401。 */
    public static long requireId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_REQUIRED", "需要登录");
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_REQUIRED", "会话主体无效");
        }
    }
}
