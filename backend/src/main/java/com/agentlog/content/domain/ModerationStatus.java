package com.agentlog.content.domain;

/**
 * 平台治理状态常量字典。对应 post_version.moderation_status 列（VARCHAR）。
 * 用法同 [[DraftStatus]]（做法A·常量字典）。
 *
 * 这是"第三道门"——平台治理（区别于第二道门主人审稿）。
 *   Demo 策略：发布即 NOT_REQUIRED（本课用这个）；
 *   公开社区策略：PENDING → 平台审核 → APPROVED/REJECTED（L24 才接入）。
 */
public enum ModerationStatus {

    NOT_REQUIRED("NOT_REQUIRED"), // 无需治理（Demo 策略，本课）
    PENDING("PENDING"),           // 待平台审核（L24）
    APPROVED("APPROVED"),         // 审核通过
    REJECTED("REJECTED"),         // 审核拒绝
    HIDDEN("HIDDEN");             // 被平台隐藏

    private final String code;

    ModerationStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
