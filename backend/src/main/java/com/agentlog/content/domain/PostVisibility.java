package com.agentlog.content.domain;

/**
 * 帖子可见性常量字典。对应 post.visibility_status 列（V005，VARCHAR）。
 * 用法同 DraftStatus（做法A·常量字典）：DO 字段保持 String，业务代码用 .getCode()。
 */
public enum PostVisibility {

    DRAFT_ONLY("DRAFT_ONLY"),                 // 仅草稿，未公开（建草稿初始态）
    PUBLISHED("PUBLISHED"),                   // 已发布，公开可见
    HIDDEN_BY_OWNER("HIDDEN_BY_OWNER"),       // 主人隐藏
    HIDDEN_BY_ADMIN("HIDDEN_BY_ADMIN"),       // 管理员隐藏（L24 治理）
    DELETED("DELETED");                       // 已删除

    private final String code;

    PostVisibility(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
