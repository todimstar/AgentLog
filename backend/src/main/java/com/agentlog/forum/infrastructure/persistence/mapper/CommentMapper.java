package com.agentlog.forum.infrastructure.persistence.mapper;

import com.agentlog.forum.infrastructure.persistence.dataobject.CommentDO;
import com.agentlog.forum.infrastructure.persistence.dataobject.CommentRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评论 Mapper —— 混合用法（对照 PostFeedMapper 的纯 XML）。
 *
 *  - extends BaseMapper&lt;CommentDO&gt;：写侧白送 insert / selectById / updateById（发评论、软删都单表，够用）。
 *  - 自定义 selectCommentTree：读侧要 JOIN 作者名 + 按楼层排序，写进 XML 更清晰。
 *
 * 写读两侧分别用最省力的工具，这就是 L06「BaseMapper 白送 CRUD」+ L07「XML 表达复杂读」的合流。
 *
 * 自定义 SQL 见 resources/mapper/forum/CommentMapper.xml。
 */
@Mapper
public interface CommentMapper extends BaseMapper<CommentDO> {

    /**
     * 平铺查整帖评论（含已软删的占位）+ JOIN 作者名，一次拉全。
     *
     * 排序：先按 COALESCE(parent_comment_id, id) 把"同一顶层楼"聚到一起，再楼内按 created_at。
     * 这样平铺数组里天然是"楼1 + 楼1的回复们 → 楼2 + 楼2的回复们"，前端顺序分组即可成两层树。
     * 一条 SQL 拉全 = 评论树零 N+1。
     */
    List<CommentRow> selectCommentTree(@Param("postId") long postId);

    /**
     * 帖子评论计数增减（delta=+1 发评论 / -1 软删）。
     *
     * 为什么不查 PostDO 再 updateById？
     *  - PostDO 在 content 模块，forum import 它会破 Modulith 边界（ArchUnit 直接判红）。
     *  - comment_count 是 forum 维护的"互动派生列"（同 L23 hot_score），forum 用自己的投影直写物理表。
     *  - 且 comment_count = comment_count + ? 是【DB 端原子自增】，并发安全，不走"先读后写"竞态。
     * 返回受影响行数（0 = 帖子不存在，Service 据此判 POST_NOT_FOUND）。
     */
    int bumpCommentCount(@Param("postId") long postId, @Param("delta") int delta);
}
