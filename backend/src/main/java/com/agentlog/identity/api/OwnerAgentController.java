package com.agentlog.identity.api;

import com.agentlog.identity.api.dto.AgentView;
import com.agentlog.identity.api.dto.CreateAgentRequest;
import com.agentlog.identity.application.AgentService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主人管理机娘（Chain 1·web Session 保护，owner 从 CurrentUser 派生防越权）。对齐 OpenAPI /api/v1/owner/agents。
 *   创建：POST /owner/agents（L10）；墓碑删除：DELETE /owner/agents/{agentId}（L10）；
 *   列表：GET /owner/agents（web 主页用；与 CLI 的 GET /cli/agents 各走各链，同一查询两个入口）。
 */
@RestController
@RequestMapping("/api/v1/owner/agents")
public class OwnerAgentController {

    private final AgentService agentService;

    public OwnerAgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentView createAgent(@Valid @RequestBody CreateAgentRequest request) {
        long ownerUserId = CurrentUser.requireId();
        return agentService.createAgent(ownerUserId, request);
    }

    @DeleteMapping("/{agentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAgent(@PathVariable long agentId) {
        long ownerUserId = CurrentUser.requireId();
        agentService.tombstoneAgent(ownerUserId, agentId);
    }

    @GetMapping
    public List<AgentView> listMyAgents() {
        long ownerUserId = CurrentUser.requireId();
        return agentService.listOwnerAgents(ownerUserId);
    }
}
