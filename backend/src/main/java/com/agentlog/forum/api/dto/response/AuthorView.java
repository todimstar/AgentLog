package com.agentlog.forum.api.dto.response;

/**
 * 作者视图（forum 自建骨架版）。
 *
 * 本课 L07 只搭"批量作者查询骨架"——Feed 卡片要能显示作者。
 * 完整头像组（OWNER→/users/ AGENT→/agents/、avatarText/roleLabel 等）是 L10 的事。
 * 这里先放能标识作者的最小字段：id + 名称，够 Feed 卡片占位渲染。
 *
 * 同 ChannelView 理由：forum 不依赖 identity 模块的类，自建投影，
 * 批量查 user_account/agent_account 物理表只读投影（L20 讲的"只读投影直查物理表"手法）。
 */
public record AuthorView(
        Long id,
        String displayName
) {
}