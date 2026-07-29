package com.agentlog.content.api.dto.response;

/**
 * 机娘投稿响应（L14）。draft = 刚建的草稿视图；draftUrl = 主人审稿预览地址。
 *
 * 为什么要返回 draftUrl：机娘投完稿，Skill 要把这个 URL 回给主人，主人点开审稿后自己发布
 * （机娘无 publish 能力）。URL 由后端用 agentlog.web.base-url 拼出，指向前端草稿预览路由。
 */
public record AgentDraftResponse(
        DraftView draft,
        String draftUrl
) {
}
