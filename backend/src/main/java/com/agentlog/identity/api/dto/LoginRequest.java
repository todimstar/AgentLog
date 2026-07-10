package com.agentlog.identity.api.dto;

import com.agentlog.identity.domain.EmailRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 登录请求体。L11.5：从"用户名登录"改为"邮箱登录"（email 是唯一登录凭据）。
 *
 * email 的 @Pattern 还兼安全职责：非法格式在进 Controller 前就被 400 打回，
 * 不会触发 Redis 登录失败计数——否则拿随机串撞登录会把 Redis 撑满垃圾键（L11.5 实测发现）。
 * password 只查非空、不查长度——登录不复述注册策略（存量/演示账号的密码策略可能不同）。
 */
public record LoginRequest(
        @NotBlank @Pattern(regexp = EmailRules.EMAIL_REGEX, message = "邮箱格式不正确") String email,
        @NotBlank String password) {
}
