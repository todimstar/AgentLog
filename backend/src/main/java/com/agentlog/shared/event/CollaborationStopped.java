package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 主人结束协作（L18）——协作的<b>唯一出口</b>。
 *
 * <h3>★ 为什么只有一个出口（主人 2026-08-15 的产品判断）</h3>
 * 蓝图状态机画了两条出边：{@code AWAITING_CONTINUATION → READY_FOR_OWNER_REVIEW}（正常收工）
 * 与 {@code PAUSED_ON_ERROR → TERMINATED}（出错放弃）。主人否决了后者：
 * <blockquote>
 * 两者对<b>草稿</b>而言结果完全相同——协作不再占着它。至于内容是删是留，
 * 那是草稿模块（L19）与删除功能的事，<b>不该由 terminate 回答</b>。
 * </blockquote>
 * ★ 判据：<b>别让一个机制回答两个问题</b>。（与 L16 主人否决 handoff TTL 是同一条。）
 * <p>额外收益：主人少做一次选择，而那次选择他<b>此刻根本没法做</b>——他还没看内容呢。
 *
 * <h3>★ 结束协作真正"终止"了什么（它不删任何内容）</h3>
 * {@code post / draft / draft_block / contribution} 四张表<b>一行都不动</b>。只做三件事：
 * ① 尾令牌 REVOKED（不能再有新人进来）② 正在写的 attempt REVOKED（它提交会被拒）
 * ③ 未完成的票 CANCELLED（让还在轮询的机娘停下来）。
 * <p>它的本质是<b>解锁</b>——把被协作占着的草稿交还给人（协作运行中草稿只读，
 * 见错误码 {@code DRAFT_LOCKED_BY_COLLAB}）。
 *
 * @param revokedAttemptId    被吊销的进行中 attempt；null = 结束时没有人在写
 * @param cancelledTicketCount 取消了几张未完成席位（DONE / FAILED_TIMEOUT 是历史，不动）
 */
public record CollaborationStopped(
        Long sessionId,
        Long ownerUserId,
        Long revokedAttemptId,
        int cancelledTicketCount,
        boolean handoffRevoked,
        Instant occurredAt
) {}
