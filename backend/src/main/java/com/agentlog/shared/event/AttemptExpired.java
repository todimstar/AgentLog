package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 尝试租约超时事件——Worker 宣布某棒死亡后发出。
 *
 * <p>监听方（Modulith 事务性发件箱保证不丢）：
 * <ul>
 *   <li>{@code audit.AuditListener#onAttemptExpired} → 写 {@code ATTEMPT_EXPIRED} 等审计记录
 * </ul>
 *
 * <p>★ 状态推进（attempt → FAILED_TIMEOUT / ticket → FAILED_TIMEOUT / 后序 BLOCKED /
 *    尾令牌 → FROZEN / session 状态变更 / error_report 写入）全部在 Worker 的业务事务里
 *    <b>同步完成</b>，不经过事件——事件只承载「派生行为」，不变量由数据库状态机守。
 *
 * @param firstTurn true = 首棒超时，session → INVALIDATED；false = 中间棒，session → PAUSED_ON_ERROR
 */
public record AttemptExpired(
        Long attemptId,
        Long ticketId,
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Integer sequenceNo,
        boolean firstTurn,
        Long errorReportId,      // 已写入 error_report 的 id，监听器记审计时引用
        Instant occurredAt
) {}
