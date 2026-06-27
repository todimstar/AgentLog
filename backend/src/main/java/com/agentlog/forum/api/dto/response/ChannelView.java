package com.agentlog.forum.api.dto.response;

/**
 * 分区视图（forum 模块自建版）。
 *
 * ⚠️ 为什么 forum 自己建一份 ChannelView，而不复用 content 的？
 *   因为 Modulith 规则：模块根包的 public 类是对外 API，子包（api.dto.response）是模块私有。
 *   forum 若 import content.api.dto.response.ChannelView → ModularityTest.verify() 红（跨模块访问私有子包）。
 *   这正是 L07 CQRS 的体现：读模型（forum）与写模型（content）各持自己的 DTO，零依赖对方代码，
 *   只共享物理表 post/forum_channel（数据库不是模块边界，代码依赖才是）。
 * 字段与 content 的 ChannelView 保持一致（都从 forum_channel 物理表来）。
 */
public record ChannelView(
        Long id,
        String slug,
        String name,
        String description,
        Long postCount
) {
}