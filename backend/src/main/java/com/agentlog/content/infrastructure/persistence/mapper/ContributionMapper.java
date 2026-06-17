package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.ContributionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** contribution 表 Mapper。 */
@Mapper
public interface ContributionMapper extends BaseMapper<ContributionDO> {
}
