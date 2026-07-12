package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.CreatePairingRequest;
import com.agentlog.identity.pairing.api.dto.CreatePairingResponse;
import com.agentlog.identity.pairing.api.dto.ExchangePairingRequest;
import com.agentlog.identity.pairing.api.dto.ExchangePairingResponse;
import com.agentlog.identity.pairing.application.DevicePairingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLI 设备配对（匿名）。对齐 OpenAPI /api/v1/cli/device-pairings。
 *
 * 这两个端点匿名（CLI 还没拿到任何 token），靠 deviceCode 自身保护：
 *   一次性、10 分钟过期、库里只存 digest。CSRF 已在 ApiSecurityConfiguration 豁免（CLI 无 Cookie）。
 */
@RestController
@RequestMapping("/api/v1/cli/device-pairings")
public class CliDevicePairingController {

    private final DevicePairingService pairingService;

    public CliDevicePairingController(DevicePairingService pairingService) {
        this.pairingService = pairingService;
    }

    /** 发起配对：返回 deviceCode + userCode + verificationUri。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatePairingResponse createPairing(@Valid @RequestBody CreatePairingRequest request) {
        return pairingService.createPairing(request);
    }

    /** 轮询换 token：PENDING（继续轮询）/ APPROVED（带 token）/ EXPIRED。 */
    @PostMapping("/token")
    public ExchangePairingResponse exchangePairing(@Valid @RequestBody ExchangePairingRequest request) {
        return pairingService.exchangePairing(request.deviceCode());
    }
}
