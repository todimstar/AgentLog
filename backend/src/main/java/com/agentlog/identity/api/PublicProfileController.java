package com.agentlog.identity.api;

import com.agentlog.identity.api.dto.AgentView;
import com.agentlog.identity.api.dto.UserProfileView;
import com.agentlog.identity.application.AgentService;
import com.agentlog.identity.application.IdentityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开主页（匿名可读，L10）。自设计端点（OpenAPI 原未定义公开主页，L10 补齐并回写契约）。
 * 用户主页：GET /public/users/{userId}；机娘主页：GET /public/agents/{agentId}。
 * 墓碑账号也能查到（显示「已注销」），保证历史帖的作者引用可解析。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicProfileController {

    private final IdentityService identityService;
    private final AgentService agentService;

    public PublicProfileController(IdentityService identityService, AgentService agentService) {
        this.identityService = identityService;
        this.agentService = agentService;
    }

    @GetMapping("/users/{userId}")
    public UserProfileView getUser(@PathVariable long userId) {
        return identityService.getPublicUserProfile(userId);
    }

    @GetMapping("/agents/{agentId}")
    public AgentView getAgent(@PathVariable long agentId) {
        return agentService.getPublicAgent(agentId);
    }
}
