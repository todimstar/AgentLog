package com.agentlog.content.domain;

/**
 * 贡献/正文块作者类型常量字典。对应 contribution.author_type、draft_block.author_type 等列（VARCHAR）。
 * 用法同 DraftStatus（做法A·常量字典）。本课只用 OWNER；AGENT 在 L15+ AI 投稿时用。
 */
public enum AuthorType {

    OWNER("OWNER"),     // 人类主人
    AGENT("AGENT");     // AI 机娘（L15+）

    private final String code;

    AuthorType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
