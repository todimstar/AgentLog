package com.agentlog.identity;

import com.agentlog.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 端认证入口：注册、登录、登出、当前用户。路径对齐 OpenAPI /api/v1/web/auth/*。
 * Controller 只做 HTTP 编排，业务在 IdentityService，认证交给 Spring Security。
 */
@RestController
@RequestMapping("/api/v1/web")
public class WebAuthController {

    private final IdentityService identityService;
    private final AuthenticationManager authenticationManager;

    // 负责把登录后的 SecurityContext 写进 HttpSession（= 让"刷新仍登录"成立的关键）。
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public WebAuthController(
            IdentityService identityService,
            AuthenticationManager authenticationManager) {
        this.identityService = identityService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@RequestBody RegisterRequest request) {
        return identityService.register(request);
    }

    @PostMapping("/auth/login")
    public UserView login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // 1. 交给 Spring Security 验证用户名+密码（内部调 AppUserDetailsService + PasswordEncoder）。
        //    失败会抛 AuthenticationException → 由 Security 翻成 401。
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        // 2. 验证通过 → 把身份放进 SecurityContext，并显式持久化到 HttpSession。
        //    Session 模式必须手动存，后续请求才能由内置过滤器从 Session 复原。
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return identityService.currentUserView(Long.parseLong(authentication.getName()));
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest) {
        // 使 Session 失效 + 清空上下文。Cookie 由浏览器在 Session 失效后自然作废。
        httpRequest.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    /** 当前登录用户（"刷新仍登录"靠它验证：带 Session Cookie 请求应返回用户而非 401）。 */
    @GetMapping("/me")
    public UserView me() {
        return identityService.currentUserView(CurrentUser.requireId());
    }
}
