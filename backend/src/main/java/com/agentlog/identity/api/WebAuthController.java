package com.agentlog.identity.api;

import com.agentlog.identity.api.dto.LoginRequest;
import com.agentlog.identity.api.dto.RegisterRequest;
import com.agentlog.identity.api.dto.SendCodeRequest;
import com.agentlog.identity.api.dto.SetAvatarRequest;
import com.agentlog.identity.api.dto.UserView;
import com.agentlog.identity.application.IdentityService;
import com.agentlog.identity.application.LoginAttemptService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 端认证入口：发验证码、注册、登录、登出、当前用户。路径对齐 OpenAPI /api/v1/web/auth/*。
 * Controller 只做 HTTP 编排，业务在 IdentityService，认证交给 Spring Security。
 *
 * L11.5：登录凭据从 username 改为 email；注册前加"发邮箱验证码"；登录加 Redis 失败限流。
 * 关键铁律不变——验证通过后仍把 SecurityContext 存进 HttpSession（Session 认证，不发 JWT）。
 */
@RestController
@RequestMapping("/api/v1/web")
public class WebAuthController {

    private final IdentityService identityService;
    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttemptService;

    // 负责把登录后的 SecurityContext 写进 HttpSession（= 让"刷新仍登录"成立的关键）。
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public WebAuthController(
            IdentityService identityService,
            AuthenticationManager authenticationManager,
            LoginAttemptService loginAttemptService) {
        this.identityService = identityService;
        this.authenticationManager = authenticationManager;
        this.loginAttemptService = loginAttemptService;
    }

    /** 发注册验证码到邮箱（注册前第一步）。匿名入口，走白名单 + CSRF 忽略。 */
    @PostMapping("/auth/send-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendCode(@Valid @RequestBody SendCodeRequest request) {
        identityService.sendRegisterCode(request.email());
    }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) {
        return identityService.register(request);
    }

    @PostMapping("/auth/login")
    public UserView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String email = request.email();

        // 0. 失败限流：连续失败 5 次锁 15 分钟（Redis）。认证前先拦，防暴力撞库。
        //    注：@Valid 已挡掉非法邮箱格式（400），到这里的 email 一定是合法格式——
        //    随机垃圾串不会进 Redis 建失败计数键。
        loginAttemptService.assertNotLocked(email);

        // 1. 交给 Spring Security 验证 email+密码（内部调 AppUserDetailsService 按 email 查 + PasswordEncoder）。
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));//旧项目此处用拦截器注入将账号密码传入SpringSecurity里当Token的？
        } catch (AuthenticationException ex) {
            loginAttemptService.recordFailure(email); // 失败计数 +1（首次失败开始计时）
            throw ex;                                 // 交给 Security 翻成 401
        }

        // 2. 验证通过 → 清失败记录 + 把身份放进 SecurityContext，并显式持久化到 HttpSession。
        //    Session 模式必须手动存，后续请求才能由内置过滤器从 Session 复原。
        loginAttemptService.clear(email);
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

    /** 设置我的头像（PUT，需登录 + CSRF）。把已 finalize 的媒体绑为头像，完成"上传→绑定→展示"。 */
    @PutMapping("/me/avatar")
    public UserView setMyAvatar(@RequestBody SetAvatarRequest request) {
        return identityService.setAvatar(CurrentUser.requireId(), request.mediaId());
    }
}
