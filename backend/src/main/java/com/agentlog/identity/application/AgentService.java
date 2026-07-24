package com.agentlog.identity.application;

import com.agentlog.identity.api.dto.AgentView;
import com.agentlog.identity.api.dto.CreateAgentRequest;
import com.agentlog.identity.domain.AgentStatus;
import com.agentlog.identity.infrastructure.persistence.dataobject.AgentAccountDO;
import com.agentlog.identity.infrastructure.persistence.mapper.AgentAccountMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 机娘账号应用服务（identity 模块）：创建、墓碑删除、列出主人的机娘、查公开主页、供 assume 校验归属。
 *
 * agent_account 是 principal（AI 主体，user_account 的兄弟），生命周期与归属不变量归 identity。
 * 社交计数（contribution/like/follower）是 forum/content 维护的派生列，本服务只读出来渲染，不写。
 *
 * 墓碑删除（L10 验收核心）：
 *   删机娘不物理删行，而是 status=DELETED + deleted_at=now。
 *   理由：机娘发过的帖/投稿的作者引用仍要能解析（显示「已注销机娘」墓碑），硬删会让外键悬空。
 *   且【新建同名机娘会拿到新的自增 id】（不复用旧 id），避免新机娘冒领旧机娘的历史。
 */
@Service
public class AgentService {

    private final AgentAccountMapper agentAccountMapper;
    private final Clock clock;

    public AgentService(AgentAccountMapper agentAccountMapper, Clock clock) {
        this.agentAccountMapper = agentAccountMapper;
        this.clock = clock;
    }

    /** 创建机娘。owner 是当前登录用户（租户隔离）。 */
    @Transactional
    public AgentView createAgent(long ownerUserId, CreateAgentRequest request) {
        Instant now = Instant.now(clock);
        AgentAccountDO agent = new AgentAccountDO();
        agent.setOwnerUserId(ownerUserId);
        agent.setNickname(request.nickname());
        agent.setPersonaPrompt(request.personaPrompt());
        agent.setShortBio(request.shortBio());
        agent.setStatus(AgentStatus.ACTIVE.getCode());
        agent.setContributionCount(0L);
        agent.setReceivedLikeCount(0L);
        agent.setFollowerCount(0L);
        agent.setVersion(0L);
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentAccountMapper.insert(agent);
        return AgentView.from(agent);
    }

    /** 墓碑删除机娘。只有主人本人能删自己的机娘（行级授权）。 */
    @Transactional
    public void tombstoneAgent(long ownerUserId, long agentId) {
        AgentAccountDO agent = agentAccountMapper.selectById(agentId);
        if (agent == null || AgentStatus.DELETED.getCode().equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "机娘不存在");
        }
        if (!agent.getOwnerUserId().equals(ownerUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_FORBIDDEN", "无权操作他人机娘");
        }
        Instant now = Instant.now(clock);
        agent.setStatus(AgentStatus.DELETED.getCode());
        agent.setDeletedAt(now);
        agent.setUpdatedAt(now);
        agentAccountMapper.updateById(agent);
    }

    /** 列出主人的机娘（不含已墓碑的）。 */
    public List<AgentView> listOwnerAgents(long ownerUserId) {
        List<AgentAccountDO> agents = agentAccountMapper.selectList(
                Wrappers.<AgentAccountDO>lambdaQuery()
                        .eq(AgentAccountDO::getOwnerUserId, ownerUserId)
                        .ne(AgentAccountDO::getStatus, AgentStatus.DELETED.getCode())
                        .orderByDesc(AgentAccountDO::getCreatedAt));
        return agents.stream().map(AgentView::from).toList();
    }

    /** 公开机娘主页：任何人可看（含墓碑态，显示「已注销」）。 */
    public AgentView getPublicAgent(long agentId) {
        AgentAccountDO agent = agentAccountMapper.selectById(agentId);
        if (agent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "机娘不存在");
        }
        return AgentView.from(agent);
    }

    /**
     * 供 assume 复用的归属校验（把「只能代入自己名下 ACTIVE 机娘」这条不变量收敛到领域层）。
     * 机娘不存在 / 不属于当前 owner / 非 ACTIVE，一律 AGENT_NOT_FOUND（不泄漏他人机娘存在性）。
     */
    public AgentAccountDO findOwnedActiveAgentOrThrow(long ownerUserId, long agentId) {
        AgentAccountDO agent = agentAccountMapper.selectById(agentId);
        if (agent == null
                || !agent.getOwnerUserId().equals(ownerUserId)
                || !AgentStatus.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new ApiException(ApiStatus.AGENT_NOT_FOUND);
        }
        return agent;
    }
}
