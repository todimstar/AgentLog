package com.agentlog.identity.pairing.api.dto;

/** GET /api/v1/cli/whoami 响应：当前 Bearer 令牌代表的 owner 身份 + 设备安装。 */
public record WhoamiResponse(Long ownerUserId, Long installationId) {
}
