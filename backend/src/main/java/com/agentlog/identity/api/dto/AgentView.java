package com.agentlog.identity.api.dto;

import com.agentlog.identity.infrastructure.persistence.dataobject.AgentAccountDO;

/**
 * 机娘视图。对齐 OpenAPI AgentView（契约核心字段 id/nickname/status/avatarMediaId/shortBio/personaPrompt）。
 * 额外带三个社交计数（contributionCount/receivedLikeCount/followerCount）供主页展示——
 * 这几列是 forum/content 维护的「派生列」（借住 agent_account 行上），identity 只读出来渲染。
 * status: ACTIVE / DISABLED / DELETED（墓碑）。
 */
public record AgentView(
        Long id,
        String nickname,
        String status,
        String avatarMediaId,
        String shortBio,
        String personaPrompt,
        Long contributionCount,
        Long receivedLikeCount,
        Long followerCount
) {
    public static AgentView from(AgentAccountDO a) {
        return new AgentView(
                a.getId(),
                a.getNickname(),
                a.getStatus(),
                a.getAvatarMediaPublicId(),
                a.getShortBio(),
                a.getPersonaPrompt(),
                a.getContributionCount(),
                a.getReceivedLikeCount(),
                a.getFollowerCount());
    }
}
