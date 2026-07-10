package com.agentlog.media.api.dto;

import java.time.Instant;

/**
 * 上传槽响应。对齐 OpenAPI UploadSlotResponse。
 *
 * mediaId：媒体公开 id（前端 finalize 时回传）；
 * uploadUrl：预签名 PUT URL——前端拿它【直传 MinIO】，不经后端；
 * method：固定 PUT；expiresAt：URL 过期时间（5 分钟，过期需重新申请）。
 */
public record UploadSlotResponse(
        String mediaId,
        String uploadUrl,
        String method,
        Instant expiresAt
) {
}
