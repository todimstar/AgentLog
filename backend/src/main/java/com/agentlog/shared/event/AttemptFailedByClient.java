package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 机娘自报失败（L18）——{@code contribution_attempt.status = 'FAILED_CLIENT'} 的<b>唯一产生路径</b>。
 *
 * <h3>★ 它补的是一个「值集列了、代码从没产生过」的状态</h3>
 * {@code FAILED_CLIENT} 从 V013 起就在 CHECK 值集里（注释标着「机娘自己报告失败（L18）」），
 * 但在本课之前<b>没有任何端点能产生它</b>。同类死值本课清了两个（另一个是 {@code READY}，
 * 那个确认为永久不产生）。
 *
 * <h3>★ 为什么值得为它单开一个端点</h3>
 * 机娘知道自己写不下去了（需求不清 / 上一棒内容有问题 / 工具报错），
 * 与其<b>干等 15 分钟让租约超时</b>，不如立刻说出来：
 * <ul>
 *   <li>后序机娘早 15 分钟知道要停；</li>
 *   <li>{@code error_report.summary} 记的是<b>真实原因</b>，而不是笼统的「租约超时」；</li>
 *   <li>主人早 15 分钟看到时间线上的红点。</li>
 * </ul>
 *
 * <p>★ 它走的状态推进与 L17 Worker 宣布超时<b>完全相同</b>（票 → 失败、后序整条尾巴 BLOCKED、
 * 尾令牌 FROZEN、session → PAUSED_ON_ERROR/INVALIDATED），只是<b>触发者不同、失败原因不同</b>。
 * 这也再次印证 L17 定的分工：<b>「何时做」可以有很多来源，「做什么」只有一份，归 collaboration。</b>
 *
 * @param reason 机娘自述的失败原因，进 {@code error_report.summary}
 */
public record AttemptFailedByClient(
        Long attemptId,
        Long ticketId,
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Integer sequenceNo,
        boolean firstTurn,
        String reason,
        Instant occurredAt
) {}
