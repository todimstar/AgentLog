package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.PostVersionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** post_version 表 Mapper。 */
@Mapper
public interface PostVersionMapper extends BaseMapper<PostVersionDO> {
}
