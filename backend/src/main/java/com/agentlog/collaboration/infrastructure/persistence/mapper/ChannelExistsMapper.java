package com.agentlog.collaboration.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * forum_channel 的存在性只读投影（collaboration 模块自持）。
 *
 * ★ 为什么 collaboration 不去用 content 模块的 ForumChannelMapper：
 *   本项目放弃了 Facade，改用 Spring Modulith 包边界强制（DRIFT D-05）——
 *   跨模块【只读】走 SQL 投影直查物理表、不 import 对方的 Java 类；【写】只碰本模块表。
 *   直接 import content 的 ForumChannelMapper 会被 ModularityTest 判红。
 *   同 content 模块查 identity 的 user_account（DraftAuthorMapper）、
 *   forum 模块查 user_account（PostFeedMapper）——第三次用同一个套路。
 *
 * ★ 为什么 start 要校验分区存在：早失败优于晚失败。
 *   planned_channel_id 有外键约束，理论上不校验也插不进去，但那样抛的是
 *   DuplicateKey 家族的约束异常（500/丑陋），而不是干净的 404 CHANNEL_NOT_FOUND。
 *   ——同「不能靠 uk_ticket_sequence 兜住并发」一个道理：
 *   **语义层的错误要在语义层报，底层约束是安全网不是第一道门。**
 */
@Mapper
public interface ChannelExistsMapper {

    /**
     * 分区是否存在（只看存在性，不看 enabled）。
     *
     * 为什么不顺带校验 enabled：content 模块的 createDraftInternal 也只做 selectById 存在性校验，
     * 两条投稿路径的口径必须一致，否则「主人能投的分区机娘投不了」会很难解释。
     * Pack 规划过 CHANNEL_DISABLED(422) 但全项目从未实装——属既存缺口，不在本课范围内单方面改口径。
     */
    boolean existsById(@Param("channelId") Long channelId);
}
