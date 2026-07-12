package com.agentlog.identity.pairing.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * owner_access_session 表的 DO。对应 V010__create_device_pairing.sql。
 * 配对成功后签发的一个 owner 会话：一行同时存 access 与 refresh 两个令牌摘要（BINARY(32)→byte[]）。
 * L13 的 bearer 过滤器将查此表（按 accessTokenDigest）校验令牌。
 */
@TableName("owner_access_session")
public class OwnerAccessSessionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long installationId;
    private Long ownerUserId;
    private byte[] accessTokenDigest;
    private byte[] refreshTokenDigest;
    private String status;
    private Instant accessExpiresAt;
    private Instant refreshExpiresAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        this.installationId = installationId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public byte[] getAccessTokenDigest() {
        return accessTokenDigest;
    }

    public void setAccessTokenDigest(byte[] accessTokenDigest) {
        this.accessTokenDigest = accessTokenDigest;
    }

    public byte[] getRefreshTokenDigest() {
        return refreshTokenDigest;
    }

    public void setRefreshTokenDigest(byte[] refreshTokenDigest) {
        this.refreshTokenDigest = refreshTokenDigest;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    public Instant getRefreshExpiresAt() {
        return refreshExpiresAt;
    }

    public void setRefreshExpiresAt(Instant refreshExpiresAt) {
        this.refreshExpiresAt = refreshExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
