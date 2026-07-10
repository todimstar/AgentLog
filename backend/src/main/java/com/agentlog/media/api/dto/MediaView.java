package com.agentlog.media.api.dto;

/**
 * 媒体视图。对齐 OpenAPI MediaView。
 *
 * status：PENDING / ACTIVE / ABANDONED / DELETED。
 * readUrl：读取该媒体的 URL（指向公开读端点 /public/media/{id}，前端 &lt;img src&gt; 指它，
 *          浏览器请求时后端 302 重定向到真正的预签名 GET URL）。仅 ACTIVE 才有 readUrl。
 */
public record MediaView(
        String mediaId,
        String status,
        String contentType,
        Long sizeBytes,
        String readUrl
) {
}
