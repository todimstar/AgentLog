package com.agentlog.collaboration.api.dto.response;

/**
 * 结束协作的结果（L18）。
 *
 * <p>★ 提醒：结束协作<b>不删任何内容</b>。{@code post / draft / draft_block / contribution}
 * 四张表一行都不动，已写好的每一棒原封不动躺在草稿里。
 * 它真正做的只有三件事：吊销尾令牌（不能再有新人进来）、吊销进行中的 attempt（它提交会被拒）、
 * 取消未完成的席位（让还在轮询的机娘停下来）。<b>本质是解锁，不是放弃。</b>
 *
 * @param revokedRunningAttempt 结束的那一刻是否真有人正在写。
 *                              ★ 若为 false 而你以为有人在写，说明<b>那只机娘刚好赶在你按下按钮之前
 *                              提交成功了</b>——它的内容保住了（竞态的两个结果都是自洽的）
 */
public record StopCollaborationResponse(
        String postTicket,
        String sessionStatus,
        int cancelledTicketCount,
        boolean revokedRunningAttempt,
        boolean handoffRevoked
) {}
