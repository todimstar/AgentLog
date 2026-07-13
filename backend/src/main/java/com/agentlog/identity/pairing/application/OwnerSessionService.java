package com.agentlog.identity.pairing.application;

import com.agentlog.identity.pairing.api.dto.RefreshTokenResponse;
import com.agentlog.identity.pairing.domain.AgentSessionStatus;
import com.agentlog.identity.pairing.domain.OwnerSessionStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.AgentActingSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.OwnerAccessSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.AgentActingSessionMapper;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.OwnerAccessSessionMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Owner 会话生命周期：refresh 令牌轮换（RTR + 盗用连坐吊销）。ADR-0002。
 *
 * 不用 @Transactional：盗用连坐吊销那条 UPDATE 必须在抛异常前落库（若包在会回滚的事务里，
 *   吊销会被一起回滚，连坐就失效了）。轮换的"旧置 REVOKED + 插新"是两条自提交语句——
 *   极端中断只会退化为"须重新配对"（fail-safe），可接受。
 */
@Service
public class OwnerSessionService {

    private final OwnerAccessSessionMapper sessionMapper;
    private final AgentActingSessionMapper agentSessionMapper;
    private final TokenService tokenService;
    private final TokenProperties tokenProps;
    private final Clock clock;

    public OwnerSessionService(OwnerAccessSessionMapper sessionMapper, AgentActingSessionMapper agentSessionMapper,
            TokenService tokenService, TokenProperties tokenProps, Clock clock) {
        this.sessionMapper = sessionMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.tokenService = tokenService;
        this.tokenProps = tokenProps;
        this.clock = clock;
    }

    /**
     * 拿 refresh token 轮换一套新令牌（RTR）。
     *   查无 → REFRESH_TOKEN_INVALID；非 ACTIVE（已轮换/吊销的 refresh 被重放）→ 连坐吊销该设备名下所有会话 + INVALID；
     *   refresh 过期 → REFRESH_TOKEN_EXPIRED；有效 → 旧会话 REVOKED、发新 access+refresh。
     */
    public RefreshTokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        OwnerAccessSessionDO session = sessionMapper.selectOne(new LambdaQueryWrapper<OwnerAccessSessionDO>()
                .eq(OwnerAccessSessionDO::getRefreshTokenDigest, tokenService.digest(rawRefreshToken)));

        if (session == null) {
            throw invalid();
        }
        // 已轮换/吊销的 refresh 被重放 → 疑似盗用 → 连坐吊销该 installation 名下所有 ACTIVE 会话。
        if (!OwnerSessionStatus.ACTIVE.getCode().equals(session.getStatus())) {
            revokeAllActiveForInstallation(session.getInstallationId(), now);
            throw invalid();
        }
        // refresh 本身过期（30d 到了）→ 去重新配对。
        if (session.getRefreshExpiresAt() == null || !session.getRefreshExpiresAt().isAfter(now)) {
            throw new ApiException(ApiStatus.REFRESH_TOKEN_EXPIRED.getStatus(),
                    ApiStatus.REFRESH_TOKEN_EXPIRED.getCode(), ApiStatus.REFRESH_TOKEN_EXPIRED.getMessage(),
                    true, List.of("RE_PAIR"));
        }

        // 轮换：旧会话 REVOKED（连带旧 access 立即失效），新会话 ACTIVE。
        session.setStatus(OwnerSessionStatus.REVOKED.getCode());
        session.setUpdatedAt(now);
        sessionMapper.updateById(session);

        String accessToken = tokenService.generateRawToken("owner_at_");
        String refreshToken = tokenService.generateRawToken("owner_rt_");
        Instant accessExp = now.plus(tokenProps.getOwnerAccessTtl());
        Instant refreshExp = now.plus(tokenProps.getOwnerRefreshTtl());

        OwnerAccessSessionDO rotated = new OwnerAccessSessionDO();
        rotated.setInstallationId(session.getInstallationId());
        rotated.setOwnerUserId(session.getOwnerUserId());
        rotated.setAccessTokenDigest(tokenService.digest(accessToken));
        rotated.setRefreshTokenDigest(tokenService.digest(refreshToken));
        rotated.setStatus(OwnerSessionStatus.ACTIVE.getCode());
        rotated.setAccessExpiresAt(accessExp);
        rotated.setRefreshExpiresAt(refreshExp);
        rotated.setCreatedAt(now);
        rotated.setUpdatedAt(now);
        sessionMapper.insert(rotated);

        return new RefreshTokenResponse(accessToken, refreshToken, accessExp);
    }

    /** 盗用响应：吊销某设备安装名下所有 ACTIVE 会话（owner + 机娘一起作废，强制重新配对）。 */
    private void revokeAllActiveForInstallation(Long installationId, Instant now) {
        sessionMapper.update(null, new LambdaUpdateWrapper<OwnerAccessSessionDO>()
                .eq(OwnerAccessSessionDO::getInstallationId, installationId)
                .eq(OwnerAccessSessionDO::getStatus, OwnerSessionStatus.ACTIVE.getCode())
                .set(OwnerAccessSessionDO::getStatus, OwnerSessionStatus.REVOKED.getCode())
                .set(OwnerAccessSessionDO::getUpdatedAt, now));
        // 连坐：该设备名下的机娘会话（agent_acting_session）一并吊销（ADR-0003 主人拍板）。
        agentSessionMapper.update(null, new LambdaUpdateWrapper<AgentActingSessionDO>()
                .eq(AgentActingSessionDO::getInstallationId, installationId)
                .eq(AgentActingSessionDO::getStatus, AgentSessionStatus.ACTIVE.getCode())
                .set(AgentActingSessionDO::getStatus, AgentSessionStatus.REVOKED.getCode())
                .set(AgentActingSessionDO::getUpdatedAt, now));
    }

    private ApiException invalid() {
        return new ApiException(ApiStatus.REFRESH_TOKEN_INVALID.getStatus(),
                ApiStatus.REFRESH_TOKEN_INVALID.getCode(), ApiStatus.REFRESH_TOKEN_INVALID.getMessage(),
                true, List.of("RE_PAIR"));
    }
}
