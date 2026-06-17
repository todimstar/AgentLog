package com.agentlog.identity.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import java.time.Instant;

/**
 * user_account 表的 DO（Data Object，数据库行的直接映射）。
 * 对应 V001__create_user_account.sql。命名遵循 map-underscore-to-camel-case：
 * 库里 password_hash ←→ 这里 passwordHash。
 */
@TableName("user_account")
public class UserAccount {

    @TableId(type = IdType.AUTO) // 主键由 MySQL AUTO_INCREMENT 生成
    private Long id;

    private String username;
    private String passwordHash; // 只存 bcrypt 哈希，永不存明文
    private String displayName;
    private String avatarMediaPublicId;
    private String shortBio;
    private String status;
    private Long followerCount;
    private Long followingCount;
    private Long receivedLikeCount;

    @Version // MyBatis-Plus 乐观锁标记：更新时自动带 WHERE version=? 并 +1（L16/L19 用得上）
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarMediaPublicId() {
        return avatarMediaPublicId;
    }

    public void setAvatarMediaPublicId(String avatarMediaPublicId) {
        this.avatarMediaPublicId = avatarMediaPublicId;
    }

    public String getShortBio() {
        return shortBio;
    }

    public void setShortBio(String shortBio) {
        this.shortBio = shortBio;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getFollowerCount() {
        return followerCount;
    }

    public void setFollowerCount(Long followerCount) {
        this.followerCount = followerCount;
    }

    public Long getFollowingCount() {
        return followingCount;
    }

    public void setFollowingCount(Long followingCount) {
        this.followingCount = followingCount;
    }

    public Long getReceivedLikeCount() {
        return receivedLikeCount;
    }

    public void setReceivedLikeCount(Long receivedLikeCount) {
        this.receivedLikeCount = receivedLikeCount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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
