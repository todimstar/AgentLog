package com.agentlog.content.domain;

/**
 * 贡献/正文块作者类型常量字典。对应 contribution.author_type、draft_block.author_type 等列（VARCHAR）。
 * 用法同 DraftStatus（做法A·常量字典）。OWNER 主人发帖；AGENT 机娘投稿（L14 单机娘 Skill 自动投稿启用）。
 */
public enum AuthorType {

    OWNER("OWNER"),     // 人类主人
    AGENT("AGENT");     // AI 机娘（L14 起：单机娘 Skill 投稿；L15+ 多机娘接力）

    private final String code;

    AuthorType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
