package com.agentlog.identity.pairing.infrastructure.persistence.mapper;

import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.OwnerAccessSessionDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** owner_access_session 单表 CRUD。 */
@Mapper
public interface OwnerAccessSessionMapper extends BaseMapper<OwnerAccessSessionDO> {
}
