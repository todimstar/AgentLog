package com.agentlog.media.application;

import com.agentlog.media.api.dto.CreateUploadSlotRequest;
import com.agentlog.media.api.dto.MediaView;
import com.agentlog.media.api.dto.UploadSlotResponse;
import com.agentlog.media.domain.MediaStatus;
import com.agentlog.media.infrastructure.MediaProperties;
import com.agentlog.media.infrastructure.persistence.dataobject.MediaObjectDO;
import com.agentlog.media.infrastructure.persistence.mapper.MediaObjectMapper;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * 媒体应用服务（media 模块）——预签名上传三步。
 *
 * 【设计精髓：文件不经后端】。后端只签发预签名 URL，前端拿 URL 直传 MinIO/S3。
 *   好处：后端不承担大文件 IO——省带宽、省内存、可水平扩展；上传压力直接落对象存储。
 *   对比传统"文件经后端中转"：后端会变成大文件管道，并发上传直接打爆应用服务器。
 *
 * 三步 + 一个读：
 *   1) createUploadSlot：建 media_object(PENDING) + 签发预签名 PUT URL（5 分钟有效）。
 *   2) （前端直传 MinIO，后端不参与）
 *   3) finalizeMedia：HEAD 对象确认真上传了 + 校验大小 → PENDING 转 ACTIVE。
 *   +  createReadRedirectUrl：签发预签名 GET URL（15 分钟），公开读端点 302 重定向过去。
 *
 * 为什么要 finalize 而不是建槽即 ACTIVE：建槽时文件还没传，PENDING 是"占位待确认"；
 *   只有 HEAD 到对象、大小合理，才转 ACTIVE。没 finalize 的 PENDING 由清理 Worker（L17）定时回收。
 */
@Service
public class MediaService {

    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final MediaObjectMapper mediaObjectMapper;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final MediaProperties props;
    private final Clock clock;

    public MediaService(MediaObjectMapper mediaObjectMapper, S3Client s3Client,
                        S3Presigner s3Presigner, MediaProperties props, Clock clock) {
        this.mediaObjectMapper = mediaObjectMapper;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.props = props;
        this.clock = clock;
    }

    /** 第 1 步：建槽 + 签发预签名 PUT URL。 */
    @Transactional
    public UploadSlotResponse createUploadSlot(long uploaderUserId, CreateUploadSlotRequest request) {
        // 校验类型与大小（契约约束，后端兜底——前端可能绕过契约直接调）。
        if (!ALLOWED_TYPES.contains(request.contentType())) {
            throw new ApiException(ApiStatus.MEDIA_TYPE_NOT_ALLOWED);
        }
        if (request.declaredSizeBytes() > props.getMaxImageSizeBytes()) {
            throw new ApiException(ApiStatus.MEDIA_TOO_LARGE);
        }

        Instant now = Instant.now(clock);
        String publicId = "med_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        // object key 用随机 publicId，不用原始文件名——防猜测、防同名覆盖。
        String objectKey = "uploads/" + publicId + extensionOf(request.contentType());

        MediaObjectDO media = new MediaObjectDO();
        media.setPublicId(publicId);
        media.setUploaderUserId(uploaderUserId);
        media.setObjectKey(objectKey);
        media.setOriginalFilename(request.originalFilename());
        media.setStatus(MediaStatus.PENDING.getCode());              // 占位待确认
        media.setContentType(request.contentType());
        media.setDeclaredSizeBytes(request.declaredSizeBytes());
        media.setAiGeneratedDeclared(request.aiGeneratedDeclared());
        media.setExpiresAt(now.plus(props.getPresignedPutTtl()));    // PENDING 清理参照
        media.setCreatedAt(now);
        media.setUpdatedAt(now);
        mediaObjectMapper.insert(media);

        // 签发预签名 PUT URL：Presigner 用密钥算出带签名的 URL，MinIO 认签名就放行上传。
        PutObjectRequest putReq = PutObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey)
                .contentType(request.contentType())
                .build();
        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(props.getPresignedPutTtl())
                .putObjectRequest(putReq)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignReq);

        return new UploadSlotResponse(publicId, presigned.url().toString(), "PUT",
                now.plus(props.getPresignedPutTtl()));
    }

    /** 第 3 步：HEAD 确认对象存在 + 校验大小 → PENDING 转 ACTIVE。 */
    @Transactional
    public MediaView finalizeMedia(long currentUserId, String publicId) {
        MediaObjectDO media = mediaObjectMapper.selectOne(
                Wrappers.<MediaObjectDO>lambdaQuery().eq(MediaObjectDO::getPublicId, publicId));
        if (media == null) {
            throw new ApiException(ApiStatus.MEDIA_NOT_FOUND);
        }
        if (!media.getUploaderUserId().equals(currentUserId)) {      // 行级授权：只能 finalize 自己的
            throw new ApiException(ApiStatus.MEDIA_FORBIDDEN);
        }
        if (MediaStatus.ACTIVE.getCode().equals(media.getStatus())) { // 幂等：重复 finalize 不报错
            return toView(media);
        }

        // HEAD 对象：确认前端真把文件传上去了，并拿到真实大小。
        // 这一步是「不信任客户端」的关键——前端说传完了不算数，问 MinIO 才算。
        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(media.getObjectKey())
                    .build());
        } catch (NoSuchKeyException e) {
            throw new ApiException(ApiStatus.MEDIA_NOT_UPLOADED);     // 槽建了但对象不在 = 没传
        }

        // 校验真实大小不超限（防前端谎报 declaredSize、实际传超大文件）。
        if (head.contentLength() != null && head.contentLength() > props.getMaxImageSizeBytes()) {
            throw new ApiException(ApiStatus.MEDIA_TOO_LARGE);
        }

        Instant now = Instant.now(clock);
        media.setStatus(MediaStatus.ACTIVE.getCode());
        media.setActualSizeBytes(head.contentLength());
        media.setFinalizedAt(now);
        media.setExpiresAt(null);                                    // ACTIVE 不再受 PENDING 清理
        media.setUpdatedAt(now);
        mediaObjectMapper.updateById(media);

        return toView(media);
    }

    /** 读重定向：签发预签名 GET URL（15 分钟），公开读端点 302 跳过去。仅 ACTIVE 可读。 */
    public String createReadRedirectUrl(String publicId) {
        MediaObjectDO media = mediaObjectMapper.selectOne(
                Wrappers.<MediaObjectDO>lambdaQuery().eq(MediaObjectDO::getPublicId, publicId));
        if (media == null || !MediaStatus.ACTIVE.getCode().equals(media.getStatus())) {
            throw new ApiException(ApiStatus.MEDIA_NOT_FOUND);
        }
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(props.getBucket())
                .key(media.getObjectKey())
                .build();
        GetObjectPresignRequest presignReq = GetObjectPresignRequest.builder()
                .signatureDuration(props.getPresignedGetTtl())
                .getObjectRequest(getReq)
                .build();
        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignReq);
        return presigned.url().toString();
    }

    private MediaView toView(MediaObjectDO m) {
        String readUrl = MediaStatus.ACTIVE.getCode().equals(m.getStatus())
                ? "/api/v1/public/media/" + m.getPublicId() : null;
        return new MediaView(m.getPublicId(), m.getStatus(), m.getContentType(),
                m.getActualSizeBytes() != null ? m.getActualSizeBytes() : m.getDeclaredSizeBytes(), readUrl);
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
