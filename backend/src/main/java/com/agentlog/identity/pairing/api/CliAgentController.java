package com.agentlog.identity.pairing.api;

import com.agentlog.identity.api.dto.AgentView;
import com.agentlog.identity.application.AgentService;
import com.agentlog.identity.pairing.api.dto.AssumeAgentRequest;
import com.agentlog.identity.pairing.api.dto.AssumeAgentResponse;
import com.agentlog.identity.pairing.application.AgentAssumeService;
import com.agentlog.identity.pairing.security.OwnerPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLI 侧机娘入口（Chain 2·owner 令牌保护）。ADR-0003。
 *   列表：GET /cli/agents（CLI `agents list` 用，owner 从 Bearer Principal 取）；
 *   代入：POST /cli/agents/{id}/assume（owner 用令牌换一把代表某机娘的 AgentActingToken）。
 * 与 web 的 GET /owner/agents 是「同一查询、两个入口、各走各链」——CLI 走 Bearer、web 走 Session。
 */
@RestController
@RequestMapping("/api/v1/cli/agents")
public class CliAgentController {

    private final AgentAssumeService assumeService;
    private final AgentService agentService;

    public CliAgentController(AgentAssumeService assumeService, AgentService agentService) {
        this.assumeService = assumeService;
        this.agentService = agentService;
    }

    /** 列出当前 owner 名下的机娘（供 `agents list`，用来挑一个去 assume）。 */
    @GetMapping
    public List<AgentView> listMyAgents(@AuthenticationPrincipal OwnerPrincipal owner) {
        return agentService.listOwnerAgents(owner.ownerUserId());
    }

    /** 代入自己名下的机娘 agentAccountId，从 sourceTool/clientRunId 这次运行换一把机娘令牌。 */
    @PostMapping("/{agentAccountId}/assume")
    public AssumeAgentResponse assume(@PathVariable Long agentAccountId,
            @AuthenticationPrincipal OwnerPrincipal owner,
            @Valid @RequestBody AssumeAgentRequest request) {
        return assumeService.assume(agentAccountId, owner, request);
    }
}
