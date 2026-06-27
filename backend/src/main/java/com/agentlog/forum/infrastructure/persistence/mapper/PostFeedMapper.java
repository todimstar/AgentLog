package com.agentlog.forum.infrastructure.persistence.mapper;

import com.agentlog.forum.api.dto.FeedQuery;
import com.agentlog.forum.api.dto.response.ChannelView;
import com.agentlog.forum.infrastructure.persistence.dataobject.PostFeedRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostBlockRow;
import com.agentlog.forum.infrastructure.persistence.dataobject.PublicPostVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * Feed 查询 Mapper —— forum 模块的【读模型】入口。
 *
 * ⚠️ 本课第一次写 MyBatis XML（L06 全用 MyBatis-Plus 的 LambdaQueryWrapper，单表够用）。
 *   Feed 带可选 channelId 筛选 + 分页 + 动态排序，设计文档要求写 XML
 *   （mybatis-guidelines：Feed 卡片归 XML；禁止 SELECT *）。
 *
 * 为什么不复用 content 的 PostMapper（BaseMapper<PostDO>）？
 *   1) content 的 PostMapper 绑定 PostDO（全字段 + 乐观锁，给写）；Feed 只要卡片缓存字段（给读）= CQRS 读模型。
 *   2) 跨模块 import 会破坏 Modulith 边界。forum 用自己的 Mapper 读同一张物理表 post，代码零依赖 content。
 *   3) 动态条件/排序 XML 表达更清晰，LambdaQueryWrapper 拼 <choose> 排序很别扭。
 *
 * 本接口【不 extends BaseMapper】：纯自定义 SQL，方法签名与 PostFeedMapper.xml 的 <select id> 一一对应。
 * 这是 L07「纯 XML Mapper」与 L06「BaseMapper 白送 CRUD」两种用法的对照。
 *
 * SQL 你親写：resources/mapper/forum/PostFeedMapper.xml。
 */
@Mapper
public interface PostFeedMapper {

    /** 查一页 Feed 卡片（扁平行 PostFeedRow，含 ownerUserId/channelId 供 Service 批量补作者/分区）。 */
    List<PostFeedRow> selectFeed(@Param("q") FeedQuery query);

    /** 查命中总数（同 WHERE 不含排序/分页）。给 PageMeta.total。 */
    long selectFeedCount(@Param("q") FeedQuery query);

    /**
     * 批量查分区（按 id 集合 IN）。Feed 拼卡片的 channel 字段用——
     * 收集一页所有 channelId 后【一次 IN 查】，避免逐卡查 channel 的 N+1。
     * 「批量查询」第一个真实落地：channel 现在就做（作者批量 L10 做头像组，本课占位）。
     */
    List<ChannelView> selectChannelsByIds(@Param("ids") Collection<Long> ids);

    // —— 单篇详情（L07 从 content 迁来，forum 用只读投影直查物理表）——

    /** 查已发布帖的当前版本头（顺 post.current_published_version_id 指针）。未发布返回 null。 */
    PublicPostVersionRow selectPublishedVersionByPostId(@Param("postId") long postId);

    /** 查某版本的所有正文块（按 display_order 升序）。 */
    List<PublicPostBlockRow> selectVersionBlocks(@Param("postVersionId") long postVersionId);

    // —— 分区列表（L07 从 content 迁来）——

    /** 列启用的分区，按 sort_order 升序。发帖选分区 + Feed 筛选用。 */
    List<ChannelView> selectEnabledChannels();
}