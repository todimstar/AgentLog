package com.agentlog.media.api;

import com.agentlog.media.api.dto.CreateUploadSlotRequest;
import com.agentlog.media.api.dto.MediaView;
import com.agentlog.media.api.dto.UploadSlotResponse;
import com.agentlog.media.application.MediaService;
import com.agentlog.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 主人媒体上传（需登录 + CSRF）。对齐 OpenAPI /api/v1/owner/media。
 * 申请上传槽：POST /owner/media/upload-slots；完成确认：POST /owner/media/{mediaId}/finalize。
 * Controller 只做编排：取登录身份 → 调 Service → 返回视图。
 */
@RestController
@RequestMapping("/api/v1/owner/media")
public class OwnerMediaController {

    private final MediaService mediaService;

    public OwnerMediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @PostMapping("/upload-slots")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadSlotResponse createUploadSlot(@Valid @RequestBody CreateUploadSlotRequest request) {
        long userId = CurrentUser.requireId();
        return mediaService.createUploadSlot(userId, request);
    }

    @PostMapping("/{mediaId}/finalize")
    public MediaView finalizeMedia(@PathVariable String mediaId) {
        long userId = CurrentUser.requireId();
        return mediaService.finalizeMedia(userId, mediaId);
    }
}
