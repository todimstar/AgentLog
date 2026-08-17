package com.agentlog.collaboration.api.dto.response;

/**
 * retry 的结果（L18）。
 *
 * <h3>★ 这里【故意删掉了】契约原本必填的 {@code attemptNo}（本课的一处漂移）</h3>
 * Pack 原始契约的 {@code RetryTicketResponse} 把 {@code attemptNo} 列为必填字段。
 * 那是蓝图 <b>A 方案</b>的遗留——A 方案里 retry 会真的建出那一行 attempt（{@code status='READY'}），
 * 返回它有意义。
 * <p>我们选了 <b>B 方案</b>（attempt 由机娘 claim-turn 时才建），那一行<b>此刻并不存在</b>：
 * <ul>
 *   <li>返回预告值 = <b>为了兼容一个已经不成立的设计，保留一个没有消费者的字段</b>；</li>
 *   <li>而且叫 {@code attemptNo} 会让人以为库里已经有那一行了——<b>比无用更坏</b>。</li>
 * </ul>
 * ★ 同一个字段在不同端点的价值可以完全相反：真正需要 {@code attemptNo} 的是<b>机娘侧</b>的
 * {@code GET /agent/contribution-tickets/{code}}（{@code >1} 说明"你在续摊，不是开新的"），
 * 那里它有真实的值——本课<b>给那个端点加上了它</b>。
 *
 * <h3>★ 为什么返回 {@code requiredAgentNickname}</h3>
 * 页面 retry 成功后要告诉主人「现在去叫谁」。这一栏由服务端给，前端不必再查一次机娘列表。
 * 而「只能是原机娘」是 {@code APPROVAL_RECORD.md:28} 冻结的规则
 * （「retry 只允许原机娘，但允许换新对话」）——<b>不需要为它写任何新代码</b>：
 * 票的 {@code required_agent_id} 本来就不变，换个机娘来领租约会撞 {@code ACPP_WRONG_AGENT}(403)。
 *
 * @param unblockedCount  连带解冻了几张后序票（递归 CTE 沿因果链走到底的结果）
 * @param handoffUnfrozen 尾令牌是否从 FROZEN 解冻回 AVAILABLE。★ 若为 true，主人多半也需要
 *                        「重新签发尾令牌」——因为那串明文他手上早就没有了
 */
public record RetryTicketResponse(
        String ticketCode,
        String status,
        Integer sequenceNo,
        Long requiredAgentId,
        String requiredAgentNickname,
        int unblockedCount,
        boolean handoffUnfrozen
) {}
