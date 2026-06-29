package com.agentlog.forum.infrastructure.persistence.mapper;

import com.agentlog.forum.infrastructure.persistence.dataobject.CollectionRecordDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 收藏 Mapper。与 ReactionMapper 同构（toggle 模式复用）。
 */
@Mapper
public interface CollectionRecordMapper extends BaseMapper<CollectionRecordDO> {

    /** INSERT IGNORE 收藏记录，返回受影响行数判加/删（语义同 ReactionMapper.insertIgnore）。 */
    int insertIgnore(@Param("c") CollectionRecordDO record);

    /** 帖子收藏计数原子增减，GREATEST 防负。 */
    int bumpPostCollectionCount(@Param("postId") long postId, @Param("delta") int delta);
}