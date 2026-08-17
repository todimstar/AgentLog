package com.agentlog.shared.event;

import java.time.Instant;

/**
 * 主人 retry 某一棒（L18）。<b>本项目第一个由「人的决策」触发的领域事件</b>。
 *
 * <h3>★ 为什么 retry 必须由人触发，而不是 Worker 自动做</h3>
 * 服务端与机娘之间是<b>「拉」不是「推」</b>的关系：机娘来问「轮到我了吗」，服务端只能回答，
 * 它<b>永远无法主动发起</b>一次机娘的写作——连对方还在不在都不知道。
 * <p>而 retry 的典型场景恰恰是<b>机娘的对话已经崩了</b>。自动重试只会把票改回可写、
 * 15 分钟后再超时、再重试 —— <b>死循环</b>，而且每轮往 error_report 里写一条。
 * <p>★ 判据：<b>这一步需要的信息只存在于系统之外</b>（你还想不想继续写、能不能把那个 AI 叫回来），
 * 所以必须停下来等人。这就是 human-in-the-loop 的判定标准，不是「人比机器聪明」。
 *
 * @param nextAttemptNo   下一次会是第几次尝试。★ 这一行<b>此刻还不存在</b>——
 *                        retry 只把票改回可写，attempt 由机娘 claim-turn 时才建
 * @param unblockedCount  连带解冻了几张后序票（递归 CTE 沿因果链走到底的结果）。
 *                        ★ 解冻到 {@code WAITING_PREDECESSOR} 而不是 {@code READY_TO_WRITE}——
 *                        前一棒还没重新写完，后序凭什么能写
 * @param handoffUnfrozen 尾令牌是否从 FROZEN 解冻回 AVAILABLE（即「还能不能有新人进来」恢复了没有）
 */
public record TicketRetried(
        Long sessionId,
        Long ownerUserId,
        Long ticketId,
        Long requiredAgentId,
        Integer sequenceNo,
        Integer nextAttemptNo,
        int unblockedCount,
        boolean handoffUnfrozen,
        Instant occurredAt
) {}
