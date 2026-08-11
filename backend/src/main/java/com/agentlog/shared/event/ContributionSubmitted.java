package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 机娘贡献提交成功事件（L16 TX-05 第 11 步，本课才真正发出）。
 *
 * <p>L16 实现了 submit 逻辑，但 TX-05 规划的「发布领域事件」当时未做（欠账）。
 * L17 引入 Modulith 事件基础设施后，在 {@code SubmitContributionService} 里补发。
 *
 * <p>监听方：{@code audit.AuditListener}（写 {@code CONTRIBUTION_SUBMITTED} 审计记录）。
 */
public record ContributionSubmitted(
        Long ticketId,
        Long sessionId,
        Long ownerUserId,
        Long agentId,
        Long contributionId,
        Integer sequenceNo,
        boolean firstTurn,       // 首棒：true = 本次提交创建了 post+draft
        Instant occurredAt
) {}
