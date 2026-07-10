package com.agentlog.identity.api.dto;

import com.agentlog.identity.domain.EmailRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发注册验证码请求体。L11.5：注册前先向邮箱发 6 位验证码。
 * 邮箱格式与注册/登录共用 EmailRules 同一条正则；服务层 issueRegisterCode 仍留兜底校验。
 */
public record SendCodeRequest(
        @NotBlank @Pattern(regexp = EmailRules.EMAIL_REGEX, message = "邮箱格式不正确") String email) {
}
