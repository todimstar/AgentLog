package com.agentlog.content.domain;

/**
 * 内容来源标识常量字典。对应 post_version.content_origin、post.content_origin_cache 列（VARCHAR）。
 * 用法同 [[DraftStatus]]（做法A·常量字典）。
 *
 * 标识一篇内容里 AI 参与的程度（AgentLog 的 AI 内容合规要求，见 01-product/AI内容标识）：
 *   普通主人发帖（本课）= HUMAN_ONLY；只要存在 AI 贡献至少 AI_ASSISTED（L15+ 协作投稿时用）。
 */
public enum ContentOrigin {

    HUMAN_ONLY("HUMAN_ONLY"),     // 纯人类创作（本课普通发帖）
    AI_ASSISTED("AI_ASSISTED"),   // 人类为主、AI 参与（L15+）
    AI_GENERATED("AI_GENERATED"); // AI 生成为主（L15+）

    private final String code;

    ContentOrigin(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
