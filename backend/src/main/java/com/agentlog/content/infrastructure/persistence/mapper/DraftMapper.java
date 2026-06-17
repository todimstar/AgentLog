package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.DraftDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** draft 表 Mapper。 */
@Mapper
public interface DraftMapper extends BaseMapper<DraftDO> {
}
