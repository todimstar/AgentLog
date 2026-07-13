package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.RefreshTokenRequest;
import com.agentlog.identity.pairing.api.dto.RefreshTokenResponse;
import com.agentlog.identity.pairing.application.OwnerSessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLI 令牌轮换（匿名·refresh token 自证）。access 已过期走不了 Bearer，故 permitAll（同配对端点）。ADR-0002。
 */
@RestController
@RequestMapping("/api/v1/cli/auth")
public class CliAuthController {

    private final OwnerSessionService ownerSessionService;

    public CliAuthController(OwnerSessionService ownerSessionService) {
        this.ownerSessionService = ownerSessionService;
    }

    /** 拿 refresh token 换新 access+refresh（RTR，旧令牌作废）。 */
    @PostMapping("/refresh")
    public RefreshTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ownerSessionService.refresh(request.refreshToken());
    }
}
