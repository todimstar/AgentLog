package com.agentlog.collaboration.api.dto.response;

import java.time.Instant;

/**
 * 开局 / 接力的共用响应（L15）。对齐活契约 StartCollaborationResponse —— start 与 claim 两个端点共用它。
 *
 * ★ 为什么两个端点能共用一个响应：因为它们在协议上做的是【同一件事】——
 *   「往因果链尾部追加一个节点，并签发新的悬空尾令牌」。
 *   start 是链为空时的特例（predecessor 为 null），join 是链非空时的通例。
 *   契约让它们同形，客户端（Skill）就能用一套代码处理两种入口——这不是偷懒，是协议设计的对称性。
 *
 * ★ nextHandoffToken 是**唯一一次**能看到这张令牌明文的机会：
 *   库里只有它的 HMAC 摘要，服务端此后再也拿不出明文。
 *   主人必须在这一刻把它复制走（或由 CLI 落进本地 state），交给下一个 AI 对话。
 *   这就是「两个 AI 对话之间零共享上下文，主人是唯一的传递媒介」这句话的物理落点。
 */
public record StartCollaborationResponse(
        String postTicket,                  // 会话对外标识 PT-16hex
        TicketView contributionTicket,      // 本次新建的席位（start=首棒；join=后续棒）
        String nextHandoffToken,            // ★ 新的悬空尾令牌【明文】，只出现这一次
        Instant nextHandoffExpiresAt        // 尾令牌何时失效（默认 24h），供客户端提示主人
) {
}
