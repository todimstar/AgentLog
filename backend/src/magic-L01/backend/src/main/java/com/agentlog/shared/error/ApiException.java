/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\main\java\com\agentlog\shared\error\ApiException.java

这是 L01 新增文件。
*/

package com.agentlog.shared.error;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    // 这是 ProblemDetail 的项目扩展码，不是旧 Result<T> 最外层的 0/1 状态码。
    private final String code;
    private final boolean recoverable;
    private final List<String> recoveryActions;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, false, List.of());
    }

    public ApiException(
            HttpStatus status,
            String code,
            String message,
            boolean recoverable,
            List<String> recoveryActions) {
        super(message);
        this.status = status;
        this.code = code;
        this.recoverable = recoverable;
        this.recoveryActions = List.copyOf(recoveryActions);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean recoverable() {
        return recoverable;
    }

    public List<String> recoveryActions() {
        return recoveryActions;
    }
}

/*
师傅解释：
  ApiException 是服务端主动抛业务错误时使用的载体。
  L01 先定最小字段：HTTP 状态、业务 code、是否可恢复、恢复动作。
  具体业务错误码后面按模块慢慢补，不在 L01 一次性铺开。

  和旧论坛项目不一样：
    旧项目 Result<T>.code 是包装层状态。
    这里 code 是 ProblemDetail 的扩展字段，例如 AUTH_SESSION_REQUIRED。
*/
