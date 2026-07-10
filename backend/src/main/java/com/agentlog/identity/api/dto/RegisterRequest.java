package com.agentlog.identity.api.dto;

import com.agentlog.identity.domain.EmailRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体。L11.5：邮箱 + 唯一用户名 + 密码 + 邮箱验证码。
 * 对照旧论坛 springBootDemo 的 RegisterDTO（email/username/password/verCode），砍掉了 displayName。
 *
 * 校验用 Jakarta Bean Validation 注解声明在 record 组件上，Controller 入参标 @Valid 触发；
 * 违反抛 MethodArgumentNotValidException → GlobalExceptionHandler 翻成 400 VALIDATION_FAILED。
 * 前端有同样校验，但后端是底线——前端可被绕过（直接 curl 调 API）。
 * 长度界限对齐契约 agentlog-openapi.yaml 的 RegisterRequest（username 2~64 / password 8~128），
 * username 上限同时对齐 user_account.username VARCHAR(64)。
 */
public record RegisterRequest(
        @NotBlank @Pattern(regexp = EmailRules.EMAIL_REGEX, message = "邮箱格式不正确") String email,
        @NotBlank @Size(min = 2, max = 64, message = "用户名长度须在 2~64 之间") String username,
        @NotBlank @Size(min = 8, max = 128, message = "密码长度须在 8~128 之间") String password,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "验证码为 6 位数字") String verCode) {
}
