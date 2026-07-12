package com.agentlog.identity.pairing.infrastructure.persistence.mapper;

import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.ClientInstallationDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** client_installation 单表 CRUD（BaseMapper 免费提供 insert/selectOne/updateById 等）。 */
@Mapper
public interface ClientInstallationMapper extends BaseMapper<ClientInstallationDO> {
}
