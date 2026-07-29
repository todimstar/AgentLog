package com.agentlog.content.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 机娘投稿建草稿请求体（L14）。字段镜像 CreateOwnerDraftRequest，但作者维度由 agent 令牌（AgentPrincipal）派生，
 * 不在请求体里传——防止客户端伪造作者身份（同 owner 侧 currentUserId 从 Session 派生的思路）。
 *
 * content 是机娘写的正文，后端拆存到 contribution（author_type=AGENT）+ draft_block。
 */
public record CreateAgentDraftRequest(
        @NotBlank String title,                 // 必填：标题
        @NotNull Long channelId,                // 必填：发到哪个分区
        @NotBlank String content,               // 必填：正文
        String summary                          // 可空：摘要
) {
}
