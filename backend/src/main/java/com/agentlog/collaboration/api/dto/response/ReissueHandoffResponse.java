package com.agentlog.collaboration.api.dto.response;

import java.time.Instant;

/**
 * 重新签发尾令牌的结果（L18）。
 *
 * <h3>★ {@code handoffToken} 是本项目仅有的几处「明文只出现一次」之一</h3>
 * 库里存的是 {@code HMAC-SHA256(pepper, 明文)}，<b>摘要算不回明文</b>。
 * 这个字段的值离开这次响应之后，<b>服务端再也无法产生它</b>——
 * 所以前端必须让主人当场复制走（一键复制按钮就是为此）。
 *
 * <p>★ 这也是为什么 UX 规格里那句「<b>可查看</b>下一棒尾令牌」被实现成「<b>重新签发</b>」：
 * 「查看」物理上不可能。判据——<b>当 UX 需求撞上安全模型时，往往不是砍需求，
 * 而是换一个能满足它的机制</b>。主人真正要的是「我能拿到一根可用的令牌」。
 *
 * @param handoffToken ★ 明文，只在这一次响应里出现
 * @param expiresAt    过期时刻。★ 通常为 <b>null</b>——L16 主人论证「独占必须有期限，
 *                     资格不必有期限」后，handoff TTL 默认关闭（DRIFT D-16）
 * @param claimCommand 给主人直接复制、粘给新 AI 对话的整条命令。
 *                     ★ 这就是「服务端怎么通知机娘」的正确形态：<b>经由主人，且让主人零思考</b>——
 *                     与 L15 接力棒必须经过人手复制粘贴是同一个架构决定
 */
public record ReissueHandoffResponse(
        String postTicket,
        String handoffToken,
        Instant expiresAt,
        String claimCommand
) {}
