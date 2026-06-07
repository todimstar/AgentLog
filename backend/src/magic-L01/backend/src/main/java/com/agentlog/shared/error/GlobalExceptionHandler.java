/*
正式目标文件：
  C:\Ep\Code\Java\AgentLog\AgentLog_start\backend\src\main\java\com\agentlog\shared\error\GlobalExceptionHandler.java

这是 L01 新增文件。
*/

package com.agentlog.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(
            ApiException exception,
            HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                exception.status(),
                exception.status().getReasonPhrase(),
                exception.getMessage(),
                exception.code(),
                exception.recoverable(),
                exception.recoveryActions(),
                request);
        return problemResponse(exception.status(), problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                exception.getMessage(),
                "VALIDATION_FAILED",
                true,
                List.of("FIX_REQUEST_BODY"),
                request);
        return problemResponse(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request) {
        ProblemDetail problem = buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Unexpected server error.",
                "INTERNAL_ERROR",
                false,
                List.of(),
                request);
        return problemResponse(HttpStatus.INTERNAL_SERVER_ERROR, problem);
    }

    private ProblemDetail buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String code,
            boolean recoverable,
            List<String> recoveryActions,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://agentlog.local/problems/" + code));
        // RFC 9457 允许扩展字段；AgentLog 用 code/traceId/recoverable 指导客户端下一步动作。
        problem.setProperty("code", code);
        problem.setProperty("traceId", traceId(request));
        problem.setProperty("recoverable", recoverable);
        problem.setProperty("recoveryActions", recoveryActions);
        return problem;
    }

    private ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, ProblemDetail problem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private String traceId(HttpServletRequest request) {
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

  L01 只定错误响应形状，注意媒体类型是 application/problem+json：
    type/title/status/detail
    code
    traceId
    recoverable
    recoveryActions

  后续前端、CLI、Skill 都可以依赖这个形状判断下一步动作。

旧论坛对照：
  springBootDemo 是 Result<T> 包 ErrorResponse。
  AgentLog 是 RFC 9457 ProblemDetail，再用扩展字段挂 code/traceId。
  所以这里不要返回 {code,message,data}，否则就退回旧项目风格了。
*/
