package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 重新签发尾令牌（L18）。旧的那根同时被吊销。
 *
 * <h3>★ 为什么这条审计记录是<b>必需品</b>而不是可选项</h3>
 * 主人在辩论时提过一个替代方案：既然旧令牌"没用了"，重签时直接<b>覆盖</b>那一行不就行了？
 * <p>技术上可行，但有一个前提被忽略了：<b>旧令牌不是"没用了"，而是"主人手上还有一份明文"</b>。
 * 如果那份明文流到了别人手里（被粘进公开的聊天记录），覆盖之后系统就<b>不知道该拒绝它</b>——
 * 表里连那条记录都没有了。
 * <p>⇒ 即便将来真的改成覆盖方案（已登记 DRIFT 遗留），<b>这条吊销流水也必须保留</b>：
 * 安全审计的基本原则是<b>凭证的签发与吊销都要留痕</b>。
 *
 * @param newTokenId          新令牌 id。明文<b>不在事件里</b>——事件会被序列化进
 *                            {@code EVENT_PUBLICATION} 表，令牌明文绝不能落库
 * @param predecessorTicketId 链尾位置没变，变的只是那张凭证
 */
public record HandoffReissued(
        Long sessionId,
        Long ownerUserId,
        Long oldTokenId,
        Long newTokenId,
        Long predecessorTicketId,
        Instant occurredAt
) {}
