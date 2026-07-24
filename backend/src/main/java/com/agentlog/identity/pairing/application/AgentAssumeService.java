package com.agentlog.identity.pairing.application;

import com.agentlog.identity.application.AgentService;
import com.agentlog.identity.pairing.api.dto.AssumeAgentRequest;
import com.agentlog.identity.pairing.api.dto.AssumeAgentResponse;
import com.agentlog.identity.pairing.domain.AgentSessionStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.AgentActingSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.AgentActingSessionMapper;
import com.agentlog.identity.pairing.security.OwnerPrincipal;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 机娘身份代入（agent assume）。ADR-0003。
 *
 * owner 持 owner 令牌，代入自己名下的某个 agent_account，换一把短命 AgentActingToken（每次运行一把）。
 * 安全不变量：只能代入 owner_user_id == 当前 owner 且 ACTIVE 的机娘，否则 404（不泄漏他人机娘存在性）。
 *
 * L10 恢复后 agent_account 有了 identity 领域层（{@link AgentService}），本服务不再裸 JdbcTemplate 查表——
 * 归属校验收敛到 AgentService.findOwnedActiveAgentOrThrow（模块内调用；pairing 是 identity 子包，非跨模块）。
 */
@Service
public class AgentAssumeService {

    private final AgentActingSessionMapper sessionMapper;
    private final AgentService agentService;
    private final TokenService tokenService;
    private final TokenProperties tokenProps;
    private final Clock clock;

    public AgentAssumeService(AgentActingSessionMapper sessionMapper, AgentService agentService,
            TokenService tokenService, TokenProperties tokenProps, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.agentService = agentService;
        this.tokenService = tokenService;
        this.tokenProps = tokenProps;
        this.clock = clock;
    }

    public AssumeAgentResponse assume(Long agentAccountId, OwnerPrincipal owner, AssumeAgentRequest request) {
        Instant now = clock.instant();

        // 安全不变量：机娘须存在、属于当前 owner、且 ACTIVE，否则统一 AGENT_NOT_FOUND 404（校验在领域层）。
        agentService.findOwnedActiveAgentOrThrow(owner.ownerUserId(), agentAccountId);

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
