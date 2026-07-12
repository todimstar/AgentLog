package com.agentlog.identity.pairing.application;

import com.agentlog.identity.pairing.api.dto.CreatePairingRequest;
import com.agentlog.identity.pairing.api.dto.CreatePairingResponse;
import com.agentlog.identity.pairing.api.dto.ExchangePairingResponse;
import com.agentlog.identity.pairing.domain.InstallationStatus;
import com.agentlog.identity.pairing.domain.OwnerSessionStatus;
import com.agentlog.identity.pairing.domain.PairingStatus;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.ClientInstallationDO;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.DevicePairingRequestDO;
import com.agentlog.identity.pairing.infrastructure.persistence.dataobject.OwnerAccessSessionDO;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.ClientInstallationMapper;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.DevicePairingRequestMapper;
import com.agentlog.identity.pairing.infrastructure.persistence.mapper.OwnerAccessSessionMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.agentlog.shared.security.TokenProperties;
import com.agentlog.shared.security.TokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备配对应用服务（identity/pairing 子命名空间）—— OAuth 2.0 设备授权流（RFC 8628）。
 *
 * 三步：
 *   createPairing ：CLI 提交 installationCode → 建 installation（PENDING）+ pairing_request，
 *                   生成 deviceCode（CLI 私藏，库存 digest）+ userCode（给人看）。
 *   confirmPairing：主人浏览器登录后输入 userCode 批准 → pairing=CONFIRMED，绑定 installation 到主人。
 *   exchangePairing：CLI 轮询 deviceCode → 未确认 PENDING / 已确认发 OwnerAccess+Refresh（库存 digest）/ 过期 EXPIRED。
 *
 * 安全要点：
 *   - deviceCode/token 明文只回客户端一次，库里只存 HMAC digest（脱库也无法冒用，见 TokenService）。
 *   - 配对码 devicePairingTtl（默认 10min）过期；过期返回 EXPIRED（验收项「过期处理」）。
 *   - exchangePairing 成功后 pairing 置 CONSUMED（一次性，防 deviceCode 重放换多个 token）。
 *   - 时间统一由注入的 Clock 取（Instant.now(clock)），便于测试推进/回拨。
 */
@Service
public class DevicePairingService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉易混 0/O/1/I
    private static final int POLL_INTERVAL_SECONDS = 3;

    private final ClientInstallationMapper installationMapper;
    private final DevicePairingRequestMapper pairingMapper;
    private final OwnerAccessSessionMapper sessionMapper;
    private final TokenService tokenService;
    private final TokenProperties tokenProps;
    private final Clock clock;
    private final String verificationUri;

    public DevicePairingService(
            ClientInstallationMapper installationMapper,
            DevicePairingRequestMapper pairingMapper,
            OwnerAccessSessionMapper sessionMapper,
            TokenService tokenService,
            TokenProperties tokenProps,
            Clock clock,
            @Value("${agentlog.pairing.verification-uri}") String verificationUri) {
        this.installationMapper = installationMapper;
        this.pairingMapper = pairingMapper;
        this.sessionMapper = sessionMapper;
        this.tokenService = tokenService;
        this.tokenProps = tokenProps;
        this.clock = clock;
        this.verificationUri = verificationUri;
    }

    /** 第 1 步：CLI 发起配对（匿名）。 */
    @Transactional
    public CreatePairingResponse createPairing(CreatePairingRequest request) {
        Instant now = Instant.now(clock);

        // installation：同 installationCode 复用（一台设备多次配对不重复建），否则新建。
        ClientInstallationDO installation = installationMapper.selectOne(
                Wrappers.<ClientInstallationDO>lambdaQuery()
                        .eq(ClientInstallationDO::getInstallationCode, request.installationCode()));
        if (installation == null) {
            installation = new ClientInstallationDO();
            installation.setInstallationCode(request.installationCode());
            installation.setDeviceName(request.deviceName());
            installation.setStatus(InstallationStatus.PENDING.getCode());
            installation.setCreatedAt(now);
            installation.setUpdatedAt(now);
            installationMapper.insert(installation);
        }

        // 生成 deviceCode（CLI 私藏）+ userCode（给人看）。
        String deviceCode = tokenService.generateRawToken("device_");
        String userCode = randomUserCode();

        DevicePairingRequestDO pairing = new DevicePairingRequestDO();
        pairing.setInstallationId(installation.getId());
        pairing.setDeviceCodeDigest(tokenService.digest(deviceCode)); // 只存 digest
        pairing.setUserCode(userCode);
        pairing.setStatus(PairingStatus.PENDING.getCode());
        pairing.setExpiresAt(now.plus(tokenProps.getDevicePairingTtl()));
        pairing.setCreatedAt(now);
        pairingMapper.insert(pairing);

        int expiresIn = (int) tokenProps.getDevicePairingTtl().toSeconds();
        return new CreatePairingResponse(deviceCode, userCode, verificationUri, expiresIn, POLL_INTERVAL_SECONDS);
    }

    /** 第 2 步：主人浏览器输入 userCode 批准（需登录，currentUserId 来自 Session）。 */
    @Transactional
    public void confirmPairing(long currentUserId, String userCode) {
        Instant now = Instant.now(clock);
        DevicePairingRequestDO pairing = pairingMapper.selectOne(
                Wrappers.<DevicePairingRequestDO>lambdaQuery()
                        .eq(DevicePairingRequestDO::getUserCode, userCode));
        if (pairing == null) {
            throw new ApiException(ApiStatus.PAIRING_NOT_FOUND);
        }
        if (now.isAfter(pairing.getExpiresAt())) {
            pairing.setStatus(PairingStatus.EXPIRED.getCode());
            pairingMapper.updateById(pairing);
            throw new ApiException(ApiStatus.PAIRING_EXPIRED);
        }
        if (!PairingStatus.PENDING.getCode().equals(pairing.getStatus())) {
            throw new ApiException(ApiStatus.PAIRING_ALREADY_HANDLED);
        }

        pairing.setStatus(PairingStatus.CONFIRMED.getCode());
        pairing.setConfirmedByUserId(currentUserId);
        pairing.setConfirmedAt(now);
        pairingMapper.updateById(pairing);

        // 绑定 installation 到主人 + 激活。
        ClientInstallationDO installation = installationMapper.selectById(pairing.getInstallationId());
        installation.setOwnerUserId(currentUserId);
        installation.setStatus(InstallationStatus.ACTIVE.getCode());
        installation.setUpdatedAt(now);
        installationMapper.updateById(installation);
    }

    /** 第 3 步：CLI 轮询换 token（匿名，凭 deviceCode）。 */
    @Transactional
    public ExchangePairingResponse exchangePairing(String deviceCode) {
        Instant now = Instant.now(clock);
        byte[] digest = tokenService.digest(deviceCode);
        DevicePairingRequestDO pairing = pairingMapper.selectOne(
                Wrappers.<DevicePairingRequestDO>lambdaQuery()
                        .eq(DevicePairingRequestDO::getDeviceCodeDigest, digest));
        if (pairing == null) {
            throw new ApiException(ApiStatus.PAIRING_NOT_FOUND);
        }
        // 过期处理（验收项）：轮询到过期返回 EXPIRED（正常轮询结果，非异常）。
        if (now.isAfter(pairing.getExpiresAt())) {
            return ExchangePairingResponse.expired();
        }
        // 还没批准：让 CLI 继续轮询。
        if (PairingStatus.PENDING.getCode().equals(pairing.getStatus())) {
            return ExchangePairingResponse.pending();
        }
        // 已消费或其它非 CONFIRMED 态：deviceCode 一次性，防重放。
        if (!PairingStatus.CONFIRMED.getCode().equals(pairing.getStatus())) {//因为来到这里之下的就是已批准的才继续操作，非批准那就是已消费态，是重放攻击/网络波动
            throw new ApiException(ApiStatus.PAIRING_ALREADY_HANDLED);
        }

        // 已确认 → 签发 OwnerAccessToken + RefreshToken（明文只回一次，库存 digest）。
        String accessToken = tokenService.generateRawToken("owner_at_");
        String refreshToken = tokenService.generateRawToken("owner_rt_");
        Instant accessExp = now.plus(tokenProps.getOwnerAccessTtl());
        Instant refreshExp = now.plus(tokenProps.getOwnerRefreshTtl());

        OwnerAccessSessionDO session = new OwnerAccessSessionDO();
        session.setInstallationId(pairing.getInstallationId());
        session.setOwnerUserId(pairing.getConfirmedByUserId());
        session.setAccessTokenDigest(tokenService.digest(accessToken));
        session.setRefreshTokenDigest(tokenService.digest(refreshToken));
        session.setStatus(OwnerSessionStatus.ACTIVE.getCode());
        session.setAccessExpiresAt(accessExp);
        session.setRefreshExpiresAt(refreshExp);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);

        // pairing 置 CONSUMED：一次性，deviceCode 不能再换 token。
        pairing.setStatus(PairingStatus.CONSUMED.getCode());
        pairingMapper.updateById(pairing);

        return ExchangePairingResponse.approved(accessToken, refreshToken, accessExp);
    }

    /** 生成 XXXX-XXXX 形式的 8 位 userCode（无易混字符，便于人念/输）。 */
    private String randomUserCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            sb.append(USER_CODE_ALPHABET.charAt(RANDOM.nextInt(USER_CODE_ALPHABET.length())));
            if (i == 3) {
                sb.append('-');
            }
        }
        return sb.toString();
    }
}
