package com.agentlog.identity.pairing.application;

import com.agentlog.identity.pairing.api.dto.AssumeAgentRequest;
import com.agentlog.identity.pairing.api.dto.AssumeAgentResponse;
import com.agentlog.identity.pairing.domain.AgentSessionStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.AgentActingSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.AgentActingSessionMapper;
import com.agentlog.identity.pairing.security.OwnerPrincipal;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 机娘身份代入（agent assume）。ADR-0003。
 *
 * owner 持 owner 令牌，代入自己名下的某个 agent_account，换一把短命 AgentActingToken（每次运行一把）。
 * 安全不变量：只能代入 owner_user_id == 当前 owner 的机娘，且 agent_account 须 ACTIVE，否则 404（不泄漏他人机娘存在性）。
 * agent_account 属别的（forum）模块、且尚无 Java 领域层——这里只做只读校验，用 JdbcTemplate 轻量查两列。
 */
@Service
public class AgentAssumeService {

    private final AgentActingSessionMapper sessionMapper;
    private final TokenService tokenService;
    private final TokenProperties tokenProps;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AgentAssumeService(AgentActingSessionMapper sessionMapper, TokenService tokenService,
            TokenProperties tokenProps, JdbcTemplate jdbcTemplate, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.tokenService = tokenService;
        this.tokenProps = tokenProps;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public AssumeAgentResponse assume(Long agentAccountId, OwnerPrincipal owner, AssumeAgentRequest request) {
        Instant now = clock.instant();

        // 安全不变量：机娘必须存在、属于当前 owner、且 ACTIVE，否则统一 404（不泄漏他人机娘）。
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT owner_user_id, status FROM agent_account WHERE id = ?", agentAccountId);
        if (rows.isEmpty()) {
            throw new ApiException(ApiStatus.AGENT_NOT_FOUND);
        }
        Long agentOwnerId = ((Number) rows.get(0).get("owner_user_id")).longValue();
        String agentStatus = (String) rows.get(0).get("status");
        if (!agentOwnerId.equals(owner.ownerUserId()) || !"ACTIVE".equals(agentStatus)) {
            throw new ApiException(ApiStatus.AGENT_NOT_FOUND);
        }

        // 铸 AgentActingToken（每次运行一把新令牌）。
        String actingToken = tokenService.generateRawToken("agent_at_");
        Instant expiresAt = now.plus(tokenProps.getAgentActingTtl());

        AgentActingSessionDO session = new AgentActingSessionDO();
        session.setAgentAccountId(agentAccountId);
        session.setInstallationId(owner.installationId());
        session.setOwnerUserId(owner.ownerUserId());
        session.setSourceTool(request.sourceTool());
        session.setClientRunId(request.clientRunId());
        session.setAccessTokenDigest(tokenService.digest(actingToken));
        session.setStatus(AgentSessionStatus.ACTIVE.getCode());
        session.setExpiresAt(expiresAt);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        return new AssumeAgentResponse(actingToken, agentAccountId, expiresAt);
    }
}
