package com.agentlog.content.infrastructure.persistence.mapper;

import com.agentlog.content.infrastructure.persistence.dataobject.DraftAuthorRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 草稿块作者的只读投影查询（content 模块自持）。
 *
 * 为什么 content 直接查 identity 的表？按本项目的模块规则（放弃 Facade，改 Spring Modulith 包边界）：
 *   跨模块【只读】走 SQL 投影直查物理表，【写】只碰本模块表。
 *   这里只 SELECT 展示字段（username/nickname/头像/状态），不 import identity 的任何 Java 类，
 *   与 forum 模块查 user_account 的做法一致（见 PostFeedMapper#selectAuthorsByUserIds）。
 */
@Mapper
public interface DraftAuthorMapper {

    /** 按用户 id 批量查主人作者的展示投影。 */
    List<DraftAuthorRow> selectOwnerAuthorsByUserIds(@Param("ids") Collection<Long> ids);

    /** 按机娘 id 批量查机娘作者的展示投影（L14 起机娘会成为草稿块的作者）。 */
    List<DraftAuthorRow> selectAgentAuthorsByAgentIds(@Param("ids") Collection<Long> ids);
}
