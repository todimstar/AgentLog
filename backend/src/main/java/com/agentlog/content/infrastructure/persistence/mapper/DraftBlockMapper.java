package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.DraftBlockDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** draft_block 表 Mapper。 */
@Mapper
public interface DraftBlockMapper extends BaseMapper<DraftBlockDO> {
}
