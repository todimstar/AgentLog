package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.ForumChannelDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** forum_channel 表 Mapper。content 模块只读分区（校验存在 + 取名做快照）。 */
@Mapper
public interface ForumChannelMapper extends BaseMapper<ForumChannelDO> {
}
