package com.agentlog.identity.pairing.security;

import com.agentlog.shared.error.ApiStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Chain 2 的 401 出口：把认证失败渲染成与 {@link com.agentlog.shared.error.GlobalExceptionHandler} 一致的 ProblemDetail。
 *
 * 为什么单独一个 entryPoint：filter 层的异常够不着 @RestControllerAdvice（advice 只管 Controller 内、
 *   跑在 DispatcherServlet 之后；filter 在其之前）。故在此统一出信封，保证 CLI 收到的错误形状一致。
 *   - OwnerTokenAuthenticationException → code(OWNER_TOKEN_*) + recoveryActions（REFRESH_TOKEN / RE_PAIR）。
 *   - 其它（如无令牌的 InsufficientAuthenticationException）→ 通用 AUTH_REQUIRED，提示去配对。
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        HttpStatus httpStatus;
        String code;
        String detail;
        List<String> recoveryActions;

        if (authException instanceof OwnerTokenAuthenticationException ex) {
            ApiStatus status = ex.apiStatus();
            httpStatus = status.getStatus();
            code = status.getCode();
            detail = status.getMessage();
            recoveryActions = ex.recoveryActions();
        } else {
            httpStatus = HttpStatus.UNAUTHORIZED;
            code = "AUTH_REQUIRED";
            detail = "需要有效的 owner 令牌，请先配对设备";
            recoveryActions = List.of("PAIR_DEVICE");
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(httpStatus, detail);
        problem.setTitle(httpStatus.getReasonPhrase());
        problem.setType(URI.create("https://agentlog.local/problems/" + code));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId(request));
        problem.setProperty("recoverable", true);
        problem.setProperty("recoveryActions", recoveryActions);

        response.setStatus(httpStatus.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return StringUtils.hasText(requestId) ? requestId : UUID.randomUUID().toString();
    }
}
