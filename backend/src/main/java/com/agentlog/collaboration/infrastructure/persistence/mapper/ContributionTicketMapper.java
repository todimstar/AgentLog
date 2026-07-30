package com.agentlog.collaboration.infrastructure.persistence.mapper;

import com.agentlog.collaboration.infrastructure.persistence.dataobject.ContributionTicketDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * contribution_ticket 的 CRUD。简单单表操作走 BaseMapper。
 *
 * 本课只用到 insert（start 建首棒票 / join 建后续票）与 selectById（读前序票判定状态）。
 */
@Mapper
public interface ContributionTicketMapper extends BaseMapper<ContributionTicketDO> {
}
