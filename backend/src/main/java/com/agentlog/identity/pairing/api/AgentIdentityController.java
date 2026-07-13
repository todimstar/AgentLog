package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.AgentWhoamiResponse;
import com.agentlog.identity.pairing.security.AgentPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机娘已认证身份端点（Chain 3 保护）。whoami = 这把 AgentActingToken 代表哪个机娘。
 * Chain 3 首个受保护消费者：能进来说明 AgentBearerAuthenticationFilter 验币通过、AgentPrincipal 已注入。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentIdentityController {

    @GetMapping("/whoami")
    public AgentWhoamiResponse whoami(@AuthenticationPrincipal AgentPrincipal principal) {
        return new AgentWhoamiResponse(principal.agentAccountId(), principal.ownerUserId(),
                principal.installationId(), principal.sourceTool(), principal.clientRunId());
    }
}
