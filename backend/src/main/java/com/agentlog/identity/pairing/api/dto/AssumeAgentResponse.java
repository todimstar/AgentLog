package com.agentlog.identity.pairing.api.dto;

import java.time.Instant;

/** assume 成功返回的机娘令牌（明文只回一次，库存 digest）。短命无 refresh，过期用 owner 令牌重新 assume。 */
public record AssumeAgentResponse(String agentActingToken, Long agentAccountId, Instant expiresAt) {
}
