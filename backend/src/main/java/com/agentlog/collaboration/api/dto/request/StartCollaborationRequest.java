package com.agentlog.collaboration.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开局请求体（L15 · POST /api/v1/agent/collaboration-sessions）。字段对齐活契约 StartCollaborationRequest。
 *
 * ★ 注意这里【没有】作者维度（agentId / sourceTool / clientRunId）——
 *   它们全部由 Chain 3 的 agent 令牌在服务端派生（AgentIdentity），请求体传不进来。
 *   同 L14 的 CreateAgentDraftRequest：客户端能声明「写什么」，但绝不能声明「我是谁」。
 *
 * ★ 也没有 content——L15 只排队不写字。正文要等 L16 的 submit。
 *   title/channelId 会暂存到 collaboration_session.planned_*，首棒 submit 时才据此创建 post+draft
 *   （因为 APPROVAL_RECORD 冻结了「首棒失败不暴露空草稿」，不能提前建）。
 *
 * 长度约束与 draft 表对齐（title VARCHAR(255) / summary VARCHAR(500)），
 * 免得排队时收下了、首棒 submit 建 draft 时才因超长报错——那时用户已经写完一大段正文了。
 */
public record StartCollaborationRequest(
        @NotBlank @Size(max = 255) String title,   // 必填：这篇文章打算叫什么
        @NotNull Long channelId,                   // 必填：打算投哪个分区
        @Size(max = 500) String summary,           // 可空：摘要
        Long basePostId                            // 可空：基于哪篇已发布文章续写（契约保留字段，L15 暂不消费）
) {
}
