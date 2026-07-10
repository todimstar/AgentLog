package com.agentlog.media.domain;

/**
 * 媒体对象状态常量字典。对应 media_object.status 列（VARCHAR）。
 * 用法同 content 模块的 DraftStatus/ModerationStatus（做法A·常量字典）。
 *
 * 生命周期：createUploadSlot 建 PENDING（占位待确认）→ finalize 核验通过转 ACTIVE。
 * 没 finalize 的 PENDING 由清理 Worker（L17）按 expires_at 定时回收。
 * 注意：identity 设头像校验读 media_object 走【跨模块只读投影直查】（不 import 本类），
 *   那边的字面量须与本字典对齐（见 IdentityService.MEDIA_STATUS_ACTIVE）。
 */
public enum MediaStatus {

    PENDING("PENDING"), // 槽已建、文件未确认（预签名 PUT 已签发，等前端直传）
    ACTIVE("ACTIVE");   // finalize 核验通过，正式可读

    private final String code;

    MediaStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
