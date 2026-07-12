package com.agentlog.identity.pairing.api;

import com.agentlog.identity.pairing.api.dto.ConfirmPairingRequest;
import com.agentlog.identity.pairing.application.DevicePairingService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览器确认配对（需登录）。主人在浏览器输入 CLI 显示的 userCode 批准设备。
 *
 * 自设计端点（OpenAPI 原缺浏览器确认这一步，但设备授权流必需，登记漂移 D-08）：
 *   POST /api/v1/web/device-pairings/confirm，走 Session + CSRF（与其它 web 写接口一致）。
 *   currentUserId 从登录态派生，绝不由客户端传入——防越权确认到别人名下。
 */
@RestController
@RequestMapping("/api/v1/web/device-pairings")
public class WebDevicePairingController {

    private final DevicePairingService pairingService;

    public WebDevicePairingController(DevicePairingService pairingService) {
        this.pairingService = pairingService;
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@Valid @RequestBody ConfirmPairingRequest request) {
        long currentUserId = CurrentUser.requireId();
        pairingService.confirmPairing(currentUserId, request.userCode());
    }
}
