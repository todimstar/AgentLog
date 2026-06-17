package com.agentlog.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request)
    {
        ProblemDetail problem = buildProblem(
                exception.status(),
                exception.status().getReasonPhrase(),
                exception.getMessage(),
                exception.code(),
                exception.recoverable(),
                exception.recoveryActions(),
                request);
        return ResponseEntity.status(exception.status()).body(problem);
    }

    //验证失败400
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(MethodArgumentNotValidException exception, HttpServletRequest request)
    {
        ProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                exception.getMessage(),
                "VALIDATION_FAILED",
                true,
                List.of("FIX_REQUEST_BODY"),
                request);
        return ResponseEntity.badRequest().body(problem);
    }

    //未知错误，内部500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception exception, HttpServletRequest request)
    {
        // 打印堆栈：否则未知异常被静默吞掉，控制台什么都看不到，无法定位（调试黑洞）。
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Unexpected server error.",
                "INTERNAL_ERROR",
                false,
                List.of(),
                request);
        return ResponseEntity.internalServerError().body(problem);
    }

    private ProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String code,
            boolean recoverable,
            List<String> recoveryActions,
            HttpServletRequest request)
    {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://agentlog.local/problems/" + code));
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId(request));
        problem.setProperty("recoverable", recoverable);
        problem.setProperty("recoveryActions", recoveryActions);
        return problem;
    }

    private String traceId(HttpServletRequest request)
    {
        String requestId = request.getHeader("X-Request-Id");
        if (StringUtils.hasText(requestId)) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }
}

/*
师傅解释：
  Controller 抛异常后，不应该每个接口自己拼错误 JSON。
  RestControllerAdvice 负责把异常统一翻译成 ProblemDetail。

  L01 只定错误响应形状：
    type/title/status/detail
    code
    traceId
    recoverable
    recoveryActions

  后续前端、CLI、Skill 都可以依赖这个形状判断下一步动作。
*/
