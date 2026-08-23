package com.agentlog.collaboration.api.dto.response;

/**
 * 结束协作的结果（L18）。
 *
 * <h3>★ 同一个动作，在两个对象上的意义相反（主人 2026-08-17 质疑后改正的表述）</h3>
 * 原先这里写「本质是解锁不是放弃」，主人反问：
 * <b>「这不应该本质是锁定吗？一堆吊销和取消，防止后续不能再动。」</b>
 * ——他说得对，我漏了最要紧的一句：<b>从哪个对象看</b>。准确的说法是：
 * <pre>
 *   对【协作】而言 → 是【终结】：尾令牌吊销、进行中的 attempt 吊销、未完成席位取消，
 *                              会话落终态，从此谁也别想再往里写。
 *   对【草稿】而言 → 是【释放】：协作运行中草稿只读（错误码 DRAFT_LOCKED_BY_COLLAB
 *                              的自愈动作正是「等待或 terminate」），
 *                              协作不结束，主人永远编辑不了、也发布不了。
 * </pre>
 * ⇒ ★ <b>「锁」这个词必须说清是锁谁</b>——同一次状态推进，对协作是收紧、对草稿是放开。
 * 之所以在用户视角强调「释放」，是因为<b>主人按下这个按钮的动机就是"我要拿回控制权"</b>。
 *
 * <p>⚠️ 无论从哪个角度看，它都<b>不删任何内容</b>：
 * {@code post / draft / draft_block / contribution} 四张表一行都不动。
 *
 * @param revokedRunningAttempt 结束的那一刻是否真有人正在写。
 *                              ★ 若为 false 而你以为有人在写，说明<b>那只机娘刚好赶在你按下按钮之前
 *                              提交成功了</b>——它的内容保住了。
 *                              <p>⚠️ 这里的竞态是 <b>stop ↔ submit</b>（主人读代码时标成了
 *                              stop ↔ retry，那是另一回事）：两者争的是<b>同一条 attempt 行</b>——
 *                              stop 要把它改 {@code REVOKED}、submit 要把它改 {@code SUCCEEDED}，
 *                              WHERE 都要求 {@code status='ACTIVE'}，所以行锁一串行就只有一个能成。
 *                              <br>而 stop ↔ retry 争的是<b>两个不同对象</b>（会话 vs 票），
 *                              那个竞态靠 retry 的 SQL 把 session 条件纳入自己的 WHERE 来解决。
 */
public record StopCollaborationResponse(
        String postTicket,
        String sessionStatus,
        int cancelledTicketCount,
        boolean revokedRunningAttempt,
        boolean handoffRevoked
) {}
