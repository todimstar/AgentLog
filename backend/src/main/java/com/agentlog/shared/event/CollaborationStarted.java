package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 协作开局事件（L15 的动作，<b>L18 才补上事件</b>）。
 *
 * <h3>★ 为什么这个事件到 L18 才出现（一个值得记住的欠账）</h3>
 * {@code V014} 的 {@code ck_audit_action_type} 从 L17 起就把 {@code COLLAB_STARTED} 列进了值集，
 * 但<b>从来没有任何代码产生过它</b>——L17 的 {@code AuditListener} 只监听了超时与提交两个事件。
 * <p>后果直到 L18 做时间线页才暴露：页面上一条协作会<b>凭空从「第 1 棒提交成功」开始</b>，
 * 前面谁开的局、谁接的棒、谁领的租约全是空白。
 *
 * <p>★ 教训：<b>CHECK 值集里有个值，不等于有代码会产生它。值集是承诺，不是实现</b>——
 * 而没有任何机器能检查「承诺有没有兑现」。同类欠账本课一共还了三笔。
 *
 * @param ticketId 首棒席位（predecessor 为 null 的那张）
 */
public record CollaborationStarted(
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Long ticketId,
        String postTicket,
        String plannedTitle,
        Instant occurredAt
) {}
