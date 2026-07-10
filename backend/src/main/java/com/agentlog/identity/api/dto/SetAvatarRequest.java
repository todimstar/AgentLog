package com.agentlog.identity.api.dto;

/** 设置头像请求体。L10-L11 修复：把一张已 finalize 的媒体设为当前用户头像。 */
public record SetAvatarRequest(String mediaId) {
}
