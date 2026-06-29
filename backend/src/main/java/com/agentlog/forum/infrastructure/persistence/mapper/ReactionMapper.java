package com.agentlog.forum.infrastructure.persistence.mapper;

import com.agentlog.forum.infrastructure.persistence.dataobject.ReactionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 点赞 Mapper。混合用法：
 *  - extends BaseMapper：白送 delete/selectList（LambdaQueryWrapper 查"用户是否已赞"、删已赞记录）。
 *  - 自定义 insertIgnore：INSERT IGNORE 走原生 SQL（MyBatis-Plus 的 insert 不支持 IGNORE）。
 *    返回受影响行数 → toggle 据此判断加/删（0=已赞过→取消，1=新赞→加）。
 */
@Mapper
public interface ReactionMapper extends BaseMapper<ReactionDO> {

    /**
     * INSERT IGNORE 一条点赞记录。返回受影响行数：
     *   1 = 之前没赞，这次加上（唯一键未冲突）
     *   0 = 之前已赞过，唯一键冲突被 IGNORE（→ 调用方据此 DELETE 取消）
     * 这是 toggle 的核心：靠 affectedRows 判加/删，不先查后插，并发靠唯一键兜底。
     */
    int insertIgnore(@Param("r") ReactionDO reaction);

    /** 帖子点赞计数原子增减。GREATEST(0, count+delta) 防止计数变负（防御性写法）。 */
    int bumpPostLikeCount(@Param("postId") long postId, @Param("delta") int delta);

    /** 评论点赞计数原子增减（comment.like_count，V006 已建该列）。同理 GREATEST 防负。 */
    int bumpCommentLikeCount(@Param("commentId") long commentId, @Param("delta") int delta);
}