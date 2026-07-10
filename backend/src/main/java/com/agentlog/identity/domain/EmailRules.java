package com.agentlog.identity.domain;

/**
 * 邮箱格式规则——单一事实源。
 * DTO 上的 @Pattern（send-code / register / login 三处）和 EmailVerificationService
 * 的兜底校验共用这一条正则，避免多处各写一份、日后悄悄漂移。
 */
public final class EmailRules {

    /** 邮箱格式（挡明显非法输入；正常邮箱都能过，边角格式宁严勿松）。 */
    public static final String EMAIL_REGEX =
            "^[A-Za-z0-9]+([_\\-.][A-Za-z0-9]+)*@[A-Za-z0-9]+([\\-.][A-Za-z0-9]+)*\\.[A-Za-z]{2,}$";

    private EmailRules() {
    }
}
