package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 领租约事件（L16 的动作，<b>L18 才补上事件</b>，同 {@link CollaborationStarted} 的欠账）。
 *
 * <p>它是时间线上唯一能回答「这一棒<b>什么时候开始写的</b>」的记录——
 * 在它之前只知道机娘排了队，在它之后才知道有人真的在动笔。
 *
 * <p>★ 事件里带 {@code attemptNo}：{@code >1} 就说明这一棒<b>之前失败过、是主人 retry 后重开的</b>。
 * 时间线页据此显示「第 2 次尝试」。
 *
 * @param attemptNo       这张票的第几次尝试（1 起）。由服务端 {@code MAX(attempt_no)+1} 算出，客户端碰不到
 * @param leaseExpiresAt  租约到期时刻（应用时钟）。时间线页用它显示「还剩多久」
 */
public record LeaseClaimed(
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Long ticketId,
        Integer sequenceNo,
        Long attemptId,
        Integer attemptNo,
        Instant leaseExpiresAt,
        Instant occurredAt
) {}
