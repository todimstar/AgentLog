package com.agentlog.identity.pairing.security;

import com.agentlog.shared.error.ApiStatus;
import java.util.List;
import org.springframework.security.core.AuthenticationException;

/**
 * Bearer 验币失败（过期/无效）时抛出，由 {@link ProblemDetailAuthenticationEntryPoint} 渲染成统一 ProblemDetail。
 * 携带 ApiStatus（决定 code/message/HTTP 状态）与 recoveryActions（告诉 CLI 下一步：REFRESH_TOKEN / RE_PAIR）。
 */
public class OwnerTokenAuthenticationException extends AuthenticationException {

    private final ApiStatus apiStatus;
    private final List<String> recoveryActions;

    public OwnerTokenAuthenticationException(ApiStatus apiStatus, List<String> recoveryActions) {
        super(apiStatus.getMessage());
        this.apiStatus = apiStatus;
        this.recoveryActions = List.copyOf(recoveryActions);
    }

    public ApiStatus apiStatus() {
        return apiStatus;
    }

    public List<String> recoveryActions() {
        return recoveryActions;
    }
}
