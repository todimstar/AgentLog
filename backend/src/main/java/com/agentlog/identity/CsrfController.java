package com.agentlog.identity;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSRF token 端点。前端在启动、登录后、登出后调用它：
 *   - Spring Security 通过 CsrfFilter 把 token 写进 XSRF-TOKEN Cookie（JS 可读）；
 *   - 这里把 token 和应回传的 Header 名返回给前端，方便其配置 axios。
 * 路径对齐 OpenAPI /api/v1/web/csrf，响应对齐 CsrfTokenResponse(headerName, token)。
 */
@RestController
@RequestMapping("/api/v1/web")
public class CsrfController {

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        // Spring Security 自动把当前请求的 CsrfToken 注入参数；访问它会触发 Cookie 下发。
        return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
    }

    public record CsrfTokenResponse(String headerName, String token) {
    }
}
