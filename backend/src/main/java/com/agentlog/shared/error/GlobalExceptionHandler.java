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
import org.springframework.security.core.AuthenticationException;
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

    /**
     * 认证失败 401（L17 补的 L05 遗留 bug）。
     *
     * <p><b>★ 为什么需要这个 handler，Security 不是自带 401 吗</b>：
     * Security 的 {@code ExceptionTranslationFilter} 只翻译<b>过滤器链上</b>抛出的
     * {@code AuthenticationException}。而 {@code WebAuthController.login} 是在
     * <b>Controller 内部</b>手动调 {@code authenticationManager.authenticate()}——
     * 那个翻译器在链的外层，管不着已经进了 Controller 的异常。
     * 于是它一路落到下面的 {@code Exception} 兜底，变成 <b>500 INTERNAL_ERROR</b>。
     *
     * <p>症状：密码打错 → 客户端收到「服务器内部错误，请重试」，
     * 而控制台明明写着 {@code BadCredentialsException: 用户名或密码错误}。
     * <b>错误码在说谎</b>，且客户端无法自愈——这正是 L13 起那套 recoveryActions 设计要避免的。
     *
     * <p><b>★ 为什么统一返回「邮箱或密码错误」而不区分两者</b>：
     * 区分开就成了账号枚举探测器（攻击者靠错误码差异筛出哪些邮箱已注册）。
     * 同 ACPP 跨租户一律 404 的思路——<b>不给探测者任何反馈信号</b>。
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationException(
            AuthenticationException exception, HttpServletRequest request) {
        // 只记一行，不打堆栈：密码错是【预期内】的业务分支，不是故障。
        // 打全堆栈会让真正的故障淹没在登录失败的噪音里。
        log.info("Authentication failed on {}: {}", request.getRequestURI(), exception.getMessage());
        ProblemDetail problem = buildProblem(
                ApiStatus.CREDENTIALS_INVALID.getStatus(),
                "Authentication failed",
                ApiStatus.CREDENTIALS_INVALID.getMessage(),
                ApiStatus.CREDENTIALS_INVALID.getCode(),
                true,                                   // 可自愈：改密码重试即可
                List.of("RETRY_WITH_CORRECT_CREDENTIALS"),
                request);
        return ResponseEntity.status(ApiStatus.CREDENTIALS_INVALID.getStatus()).body(problem);
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
