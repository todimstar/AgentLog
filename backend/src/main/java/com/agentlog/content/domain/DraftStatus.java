package com.agentlog.content.domain;

/**
 * 草稿状态常量字典。对应 draft.status 列（V005，VARCHAR）。
 *
 * 用法（做法A·常量字典）：DO 的 status 字段保持 String，业务代码里用本枚举当"选项"，
 * 存库 draft.setStatus(DraftStatus.EDITABLE.getCode())，判断 DraftStatus.EDITABLE.getCode().equals(...)。
 * 好处：合法值集中在此、IDE 可补全、写错即编译错；又不引入 MyBatis 枚举转换的"魔法层"，存什么一目了然。
 *
 * code 与枚举名解耦（虽当前一致）：将来若想改 Java 端常量名，存库值不受影响。
 */
public enum DraftStatus {

    EDITABLE("EDITABLE"),                              // 可编辑（建草稿初始态）
    LOCKED_BY_COLLAB("LOCKED_BY_COLLAB"),             // 被 AI 协作锁定（L15+）
    READY_FOR_OWNER_REVIEW("READY_FOR_OWNER_REVIEW"), // 待主人审稿（L20）
    PUBLISHED("PUBLISHED"),                            // 已发布
    DISCARDED("DISCARDED");                            // 已废弃

    private final String code;

    DraftStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
