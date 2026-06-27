package com.agentlog.forum.api.dto.response;

/**
 * 分页元信息。Feed 用 offset 分页（V1），未来可升级 cursor 分页。
 */
public record PageMeta(
        int page,
        int size,
        long total
) {
}