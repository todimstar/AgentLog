package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.WhoamiResponse;
import com.agentlog.identity.pairing.security.OwnerPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLI 已认证身份端点（Chain 2 保护）。whoami = 令牌代表谁 —— 兼作 CLI `auth status` 后端。
 * 这是 Chain 2 的第一个受保护消费者：能进到这里，说明 OwnerBearerAuthenticationFilter 验币通过、principal 已注入。
 */
@RestController
@RequestMapping("/api/v1/cli")
public class CliIdentityController {

    @GetMapping("/whoami")
    public WhoamiResponse whoami(@AuthenticationPrincipal OwnerPrincipal principal) {
        return new WhoamiResponse(principal.ownerUserId(), principal.installationId());
    }
}
