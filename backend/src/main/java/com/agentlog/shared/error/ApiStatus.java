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

    // —— 设备配对（L12 identity/pairing）——
    PAIRING_NOT_FOUND(HttpStatus.NOT_FOUND, "PAIRING_NOT_FOUND", "配对请求不存在"),
    PAIRING_EXPIRED(HttpStatus.GONE, "PAIRING_EXPIRED", "配对码已过期"),
    PAIRING_ALREADY_HANDLED(HttpStatus.CONFLICT, "PAIRING_ALREADY_HANDLED", "配对请求已处理"),

    // —— CLI Bearer 令牌（L13 Chain 2）—— ⚠️错误码冻结面，待接入 Pack 登记 DRIFT
    OWNER_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "OWNER_TOKEN_EXPIRED", "令牌已过期，请刷新"),
    OWNER_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "OWNER_TOKEN_INVALID", "令牌无效，请重新配对"),

    // —— refresh 轮换（L13 Chain 2 · ADR-0002）—— ⚠️错误码冻结面，待接入 Pack 登记 DRIFT
    REFRESH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", "刷新令牌已过期，请重新配对"),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID", "刷新令牌无效，请重新配对"),

    // —— 机娘代入 assume（L13 Chain 3 · ADR-0003）—— ⚠️错误码冻结面，待接入 Pack 登记 DRIFT
    AGENT_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AGENT_TOKEN_EXPIRED", "机娘令牌已过期，请重新代入"),
    AGENT_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AGENT_TOKEN_INVALID", "机娘令牌无效，请重新代入"),
    AGENT_NOT_FOUND(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "机娘不存在或不属于你"),
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
