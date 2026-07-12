package com.agentlog.identity.pairing.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * device_pairing_request 表的 DO。对应 V010__create_device_pairing.sql。
 * 一次 auth login 一条：存 deviceCode 的 HMAC 摘要（BINARY(32)→byte[]）+ 给人看的 userCode + 过期时间。
 */
@TableName("device_pairing_request")
public class DevicePairingRequestDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long installationId;
    private byte[] deviceCodeDigest;
    private String userCode;
    private String status;
    private Long confirmedByUserId;
    private Instant expiresAt;
    private Instant confirmedAt;
    private Instant createdAt;

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

    public byte[] getDeviceCodeDigest() {
        return deviceCodeDigest;
    }

    public void setDeviceCodeDigest(byte[] deviceCodeDigest) {
        this.deviceCodeDigest = deviceCodeDigest;
    }

    public String getUserCode() {
        return userCode;
    }

    public void setUserCode(String userCode) {
        this.userCode = userCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getConfirmedByUserId() {
        return confirmedByUserId;
    }

    public void setConfirmedByUserId(Long confirmedByUserId) {
        this.confirmedByUserId = confirmedByUserId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
