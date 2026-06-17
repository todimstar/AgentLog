package com.agentlog.shared.error;

import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final boolean recoverable;
    private final List<String> recoveryActions;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, false, List.of());
    }
    public ApiException(ApiStatus apiStatus){
        this(apiStatus.getStatus(), apiStatus.getCode(), apiStatus.getMessage(),false,List.of());
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
