package com.agentlog.media.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * media_object 表 DO。对应 V002__create_media_object.sql（表在 L03 就建好了，L11 才用上）。
 * 手写 getter/setter，不用 Lombok（学习项目透明 > 省行数）。
 *
 * 生命周期：PENDING（建槽，对象未上传）→ ACTIVE（finalize 确认上传）
 *          → ABANDONED（超时未传，清理 Worker 回收，L17）/ DELETED。
 * public_id：对外暴露的随机 id（med_xxx），不暴露自增主键；
 * object_key：S3 里的随机存储键，不用原始文件名（防猜测、防同名覆盖）。
 */
@TableName("media_object")
public class MediaObjectDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String publicId;
    private Long uploaderUserId;
    private String objectKey;
    private String originalFilename;
    private String status;
    private String contentType;
    private Long declaredSizeBytes;
    private Long actualSizeBytes;
    private String checksumSha256;
    private Boolean aiGeneratedDeclared;
    private Boolean aiMetadataDetected;
    private Instant expiresAt;
    private Instant finalizedAt;
    private Instant deletedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }

    public Long getUploaderUserId() { return uploaderUserId; }
    public void setUploaderUserId(Long uploaderUserId) { this.uploaderUserId = uploaderUserId; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getDeclaredSizeBytes() { return declaredSizeBytes; }
    public void setDeclaredSizeBytes(Long declaredSizeBytes) { this.declaredSizeBytes = declaredSizeBytes; }

    public Long getActualSizeBytes() { return actualSizeBytes; }
    public void setActualSizeBytes(Long actualSizeBytes) { this.actualSizeBytes = actualSizeBytes; }

    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }

    public Boolean getAiGeneratedDeclared() { return aiGeneratedDeclared; }
    public void setAiGeneratedDeclared(Boolean aiGeneratedDeclared) { this.aiGeneratedDeclared = aiGeneratedDeclared; }

    public Boolean getAiMetadataDetected() { return aiMetadataDetected; }
    public void setAiMetadataDetected(Boolean aiMetadataDetected) { this.aiMetadataDetected = aiMetadataDetected; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(Instant finalizedAt) { this.finalizedAt = finalizedAt; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
