package com.agentlog.collaboration.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 接力请求体（L15 · POST /api/v1/agent/collaboration-handoffs/claim）。对齐活契约 ClaimHandoffRequest。
 *
 * 只有一个字段：主人转交过来的接力棒明文。
 *
 * ★ 为什么不需要传 postTicket / sessionId：**令牌自己知道它属于哪个会话**
 *   （handoff_token.session_id）。让客户端再传一遍不但冗余，还多一个可篡改的输入
 *   （传 A 的令牌 + B 的 sessionId 会逼服务端做一次一致性校验，凭空多一条错误分支）。
 *   凭证自带上下文，是 opaque token 相对「id + 密码」的一个结构性优点。
 *
 * ★ 明文令牌走 body 而不是 URL：URL 会进 access log、浏览器历史、Referer 头。
 *   （对比 L16 的 LeaseToken 走 X-Turn-Lease-Token 请求头——那是因为 submit 的 body 已被正文占用。）
 */
public record ClaimHandoffRequest(
        @NotBlank String handoffToken
) {
}
