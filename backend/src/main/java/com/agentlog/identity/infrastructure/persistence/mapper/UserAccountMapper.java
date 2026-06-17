package com.agentlog.identity.infrastructure.persistence.mapper;

import com.agentlog.identity.infrastructure.persistence.dataobject.UserAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 继承 BaseMapper 即免费获得 insert/selectById/updateById 等单表 CRUD（设计文档：简单 CRUD 用 MyBatis-Plus）。
 * 复杂查询才写 XML，这里用不到。
 */
@Mapper
public interface UserAccountMapper extends BaseMapper<UserAccount> {
}
