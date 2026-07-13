package com.agentlog.identity.pairing.security;

import com.agentlog.shared.error.ApiStatus;
import java.util.List;
import org.springframework.security.core.AuthenticationException;

/**
 * Chain 3 验币失败（过期/无效）时抛出，由 {@link ProblemDetailAuthenticationEntryPoint} 渲染统一 ProblemDetail。
 * recoveryActions 告诉机娘客户端下一步：RE_ASSUME（用 owner 令牌重新代入）。
 */
public class AgentTokenAuthenticationException extends AuthenticationException implements RecoverableAuthError {

    private final ApiStatus apiStatus;
    private final List<String> recoveryActions;

    public AgentTokenAuthenticationException(ApiStatus apiStatus, List<String> recoveryActions) {
        super(apiStatus.getMessage());
        this.apiStatus = apiStatus;
        this.recoveryActions = List.copyOf(recoveryActions);
    }

    @Override
    public ApiStatus apiStatus() {
        return apiStatus;
    }

    @Override
    public List<String> recoveryActions() {
        return recoveryActions;
    }
}
