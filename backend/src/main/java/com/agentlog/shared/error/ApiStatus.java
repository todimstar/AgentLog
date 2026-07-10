package com.agentlog.shared.error;

import org.springframework.http.HttpStatus;

public enum ApiStatus {
    // —— 草稿 / 发布（L06 content）——
    CHANNEL_NOT_FOUND(HttpStatus.NOT_FOUND, "CHANNEL_NOT_FOUND", "分区不存在"),
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND,"DRAFT_NOT_FOUND","草稿不存在"),
    DRAFT_FORBIDDEN(HttpStatus.FORBIDDEN, "DRAFT_FORBIDDEN", "无权操作他人草稿"),
    DRAFT_VERSION_CONFLICT(HttpStatus.CONFLICT, "DRAFT_VERSION_CONFLICT", "草稿已被修改，请刷新"),
    DRAFT_ALREADY_PUBLISHED(HttpStatus.CONFLICT, "DRAFT_ALREADY_PUBLISHED", "草稿已发布"),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "帖子不存在或未发布"),

    // —— 评论（L08 forum）——
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMENT_NOT_FOUND", "评论不存在"),
    COMMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMENT_FORBIDDEN", "无权操作他人评论"),

    // —— 点赞 / 收藏（L09 forum）——
    REACTION_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "REACTION_TARGET_NOT_FOUND", "点赞目标不存在"),
    POST_NOT_FOUND_REACTION(HttpStatus.NOT_FOUND, "POST_NOT_FOUND_REACTION", "帖子不存在"),

    // —— 媒体上传（L11 media）——
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "媒体不存在"),
    MEDIA_FORBIDDEN(HttpStatus.FORBIDDEN, "MEDIA_FORBIDDEN", "无权操作他人媒体"),
    MEDIA_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "MEDIA_TYPE_NOT_ALLOWED", "仅支持 JPEG/PNG/WebP"),
    MEDIA_TOO_LARGE(HttpStatus.BAD_REQUEST, "MEDIA_TOO_LARGE", "图片超过大小限制"),
    MEDIA_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "MEDIA_NOT_UPLOADED", "文件尚未上传完成"),

    // —— 邮箱登录 / 验证码（L11.5 identity）——
    EMAIL_TAKEN(HttpStatus.CONFLICT, "EMAIL_TAKEN", "邮箱已被注册"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "USERNAME_TAKEN", "用户名已被占用"),
    EMAIL_INVALID(HttpStatus.BAD_REQUEST, "EMAIL_INVALID", "邮箱格式不正确"),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_EXPIRED", "验证码已过期，请重新获取"),
    VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "VERIFICATION_CODE_INVALID", "验证码错误"),
    CODE_SEND_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "CODE_SEND_TOO_FREQUENT", "验证码已发送，请稍后再试"),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED", "登录失败次数过多，账号已临时锁定，请稍后再试"),
    ;

    private final HttpStatus status;
    private final String code;
    private final String message;

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    ApiStatus(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
