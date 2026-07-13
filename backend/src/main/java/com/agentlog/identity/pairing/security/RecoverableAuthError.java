package com.agentlog.identity.pairing.security;

import com.agentlog.shared.error.ApiStatus;
import java.util.List;

/**
 * 可恢复的认证失败：携带 ApiStatus（决定 code/message/HTTP）+ recoveryActions（告诉客户端下一步）。
 * Owner/Agent 两条 Bearer 链的认证异常都实现它，好让 {@link ProblemDetailAuthenticationEntryPoint} 统一出信封。
 */
public interface RecoverableAuthError {

    ApiStatus apiStatus();

    List<String> recoveryActions();
}
