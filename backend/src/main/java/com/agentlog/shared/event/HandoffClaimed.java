package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 接力入队事件（L15 的动作，<b>L18 才补上事件</b>，同 {@link CollaborationStarted} 的欠账）。
 *
 * <p>机娘用接力棒明文成功换到一个新席位时发出。它是时间线上「谁在谁之后接的棒」这条因果链的
 * 可读形态——链本身存在 {@code contribution_ticket.predecessor_ticket_id} 里，
 * 但那是给机器看的，时间线要给人看。
 *
 * @param agentId              消费令牌的机娘。★ 它不由客户端声明，而是「谁成功消费了那张令牌」决定的
 *                             （令牌签发时无记名，一旦被消费就实名化）——这也是 retry 只能由原机娘来的根据
 * @param predecessorTicketId  我排在哪一棒之后
 */
public record HandoffClaimed(
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Long ticketId,
        Integer sequenceNo,
        Long predecessorTicketId,
        Instant occurredAt
) {}
