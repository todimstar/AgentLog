package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.AssumeAgentRequest;
import com.agentlog.identity.pairing.api.dto.AssumeAgentResponse;
import com.agentlog.identity.pairing.application.AgentAssumeService;
import com.agentlog.identity.pairing.security.OwnerPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLI 侧机娘代入入口（Chain 2·owner 令牌保护）。owner 用自己的令牌换一把代表某机娘的 AgentActingToken。ADR-0003。
 */
@RestController
@RequestMapping("/api/v1/cli/agents")
public class CliAgentController {

    private final AgentAssumeService assumeService;

    public CliAgentController(AgentAssumeService assumeService) {
        this.assumeService = assumeService;
    }

    /** 代入自己名下的机娘 agentAccountId，从 sourceTool/clientRunId 这次运行换一把机娘令牌。 */
    @PostMapping("/{agentAccountId}/assume")
    public AssumeAgentResponse assume(@PathVariable Long agentAccountId,
            @AuthenticationPrincipal OwnerPrincipal owner,
            @Valid @RequestBody AssumeAgentRequest request) {
        return assumeService.assume(agentAccountId, owner, request);
    }
}
