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

    // —— ACPP 接力棒（L15 collaboration · ADR-0005）—— 冻结面已登记 DRIFT D-15
    //
    // 这批码的 HTTP 语义是刻意区分的，客户端（CLI/Skill）靠它们做自愈决策
    // （对齐 Pack 08-skill/agentlog/references/error-actions.md 的动作表）：
    //   404 NOT_FOUND  → 令牌压根不存在，【或存在但不属于你】。★ 跨主人一律 404 而非 403：
    //                    403 等于承认"这东西存在、只是不给你"，会泄漏资源存在性、给枚举者反馈。
    //                    见 Pack 09-security/多租户授权与行级隔离.md §5。
    //   409 CONFLICT   → 令牌真实存在且属于你，但当前状态不允许消费（已用过 / 被冻结 / 被吊销）。
    //                    "冲突"= 你的意图和资源的当前状态打架，重试同一个请求不会变好，
    //                    要换个东西（向主人索取最新尾令牌）。
    //   410 GONE       → 曾经有效、现已永久失效（过期）。410 比 404 多告诉客户端一件事：
    //                    "别再拿这个试了"，同 PAIRING_EXPIRED 用 410 的口径。
    ACPP_HANDOFF_NOT_FOUND(HttpStatus.NOT_FOUND, "ACPP_HANDOFF_NOT_FOUND", "接力令牌不存在"),
    ACPP_HANDOFF_CONSUMED(HttpStatus.CONFLICT, "ACPP_HANDOFF_CONSUMED", "接力令牌已被使用，请向主人索取最新的尾令牌"),
    ACPP_HANDOFF_EXPIRED(HttpStatus.GONE, "ACPP_HANDOFF_EXPIRED", "接力令牌已过期，请向主人索取新的尾令牌"),
    ACPP_HANDOFF_FROZEN(HttpStatus.CONFLICT, "ACPP_HANDOFF_FROZEN", "接力链已冻结（前序失败），请停止并报告主人"),
    ACPP_HANDOFF_REVOKED(HttpStatus.CONFLICT, "ACPP_HANDOFF_REVOKED", "接力令牌已被吊销，本次协作已结束"),
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
