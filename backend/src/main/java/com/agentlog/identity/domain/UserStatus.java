package com.agentlog.identity.domain;

/**
 * 用户账号状态常量字典。对应 user_account.status 列（VARCHAR）。
 * 用法同 content 模块的 [[DraftStatus]]/[[ModerationStatus]]（做法A·常量字典）。
 *
 * 现阶段只有 ACTIVE；封禁 / 注销等状态等后续课程引入时再扩枚举值。
 * 注意：forum 读 user_account 走【跨模块只读投影直查】（不 import 本类），
 *   那边的字面量须与本字典对齐（见 FeedService.AUTHOR_STATUS_ACTIVE）。
 */
public enum UserStatus {

    ACTIVE("ACTIVE"); // 正常可用

    private final String code;

    UserStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
