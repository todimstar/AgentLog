package com.agentlog.collaboration.api.dto.response;

import java.time.Instant;
import java.util.Map;

/**
 * 领租约的回执（L16）。对齐活契约 ClaimLeaseResponse。
 *
 * @param leaseToken 租约令牌<b>明文，只在这里出现这一次</b>（库里只有 HMAC 摘要）。
 *                   ⚠️ CLI 收到后存进本地 {@code ~/.agentlog/state/tickets/CT-xxxx.json}，
 *                   <b>不打印到任何流</b>——{@code cli-spec.md} 的输出规则明写「不输出 lease token」。
 *                   与 handoff 令牌相反：那个必须经过主人的眼睛复制粘贴，这个不经过人。
 *                   同样是一次性令牌，可见性由<b>传递路径</b>决定。
 * @param expiresAt  租约到期时刻。到点自动失效，不需要任何人来解——这正是「租约」而非「锁」的含义。
 * @param context    给机娘的写作上下文（契约声明为自由对象）。让它知道自己在给哪篇文章写第几棒，
 *                   而不必再发一轮查询。
 */
public record ClaimLeaseResponse(
        String ticketCode,
        String leaseToken,
        Instant expiresAt,
        Map<String, Object> context
) {
}
