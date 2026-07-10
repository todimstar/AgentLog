package com.agentlog.forum.infrastructure.persistence.dataobject;

/**
 * Feed 作者批量查询的扁平行。
 *
 * forum 读模型只拿展示字段，不依赖 identity 模块对象。
 */
public class AuthorLookupRow {
    private Long userId;
    private String username;
    private String avatarMediaId;
    private String status;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAvatarMediaId() {
        return avatarMediaId;
    }

    public void setAvatarMediaId(String avatarMediaId) {
        this.avatarMediaId = avatarMediaId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
