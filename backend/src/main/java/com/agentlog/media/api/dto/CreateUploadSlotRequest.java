package com.agentlog.media.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 申请上传槽请求。对齐 OpenAPI CreateUploadSlotRequest。
 *
 * contentType 限 image/jpeg|png|webp（Service 兜底校验）；declaredSizeBytes 上限 5MB（Service 校验）。
 * 注意：这里是「声明」大小——前端说要传多大。真实大小 finalize 时 HEAD 拿准，防前端谎报。
 */
public record CreateUploadSlotRequest(
        @NotBlank String originalFilename,
        @NotBlank String contentType,
        @NotNull @Positive Long declaredSizeBytes,
        boolean aiGeneratedDeclared
) {
}
