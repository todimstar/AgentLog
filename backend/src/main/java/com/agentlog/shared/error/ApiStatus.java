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
    // ★ L17 补（主人验收 L17 时发现的 L05 遗留 bug）：密码错误原本返回 500 INTERNAL_ERROR。
    //   根因：WebAuthController 在【Controller 内部】手动调 authenticationManager.authenticate()，
    //   抛出的 AuthenticationException 不经过 Security 的 ExceptionTranslationFilter
    //   （那个翻译器在过滤器链上，管不着已经进了 Controller 的异常），于是落到 Exception 兜底 → 500。
    //   ★ 为什么必须修：500 的语义是「服务器坏了，重试吧」，而真相是「你密码打错了」——
    //     这个错误码在说谎，且客户端无法自愈（这正是 L13 起 recoveryActions 那套设计要避免的）。
    //   ★ 为什么不区分「用户不存在」和「密码错误」：会变成账号枚举探测器
    //     （攻击者靠错误码差异就能筛出哪些邮箱已注册）。统一口径，同 ACPP 跨租户一律 404 的思路。
    CREDENTIALS_INVALID(HttpStatus.UNAUTHORIZED, "CREDENTIALS_INVALID", "邮箱或密码错误"),

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

    // —— ACPP 席位与租约（L16 attempt/lease · ADR-0006）—— 冻结面已登记 DRIFT D-16
    //
    // 延续上面那套 404/409/410 的语义分工，另加一个 403：
    //   403 FORBIDDEN → 资源【存在、也属于你的主人】，但这一棒不是【你这个机娘】的。
    //                   ★ 这里用 403 而不是 404，与跨主人的 404 口径不冲突：
    //                     跨主人返 404 是防【别的主人】枚举资源；而同一主人名下的两个机娘
    //                     本来就彼此可见（都是你派出去的），藏起来没有安全收益，
    //                     反而让机娘拿不到"该换回原机娘"这条自愈信息。
    //                     ——泄漏边界按【租户】划，不按【机娘】划。
    ACPP_TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "ACPP_TICKET_NOT_FOUND", "席位不存在"),
    ACPP_TICKET_WAITING(HttpStatus.CONFLICT, "ACPP_TICKET_WAITING", "前一棒尚未完成，请稍后重试"),
    ACPP_TICKET_BLOCKED(HttpStatus.CONFLICT, "ACPP_TICKET_BLOCKED", "前序失败导致本棒被阻塞，请停止并报告主人"),
    ACPP_TICKET_NOT_WRITABLE(HttpStatus.CONFLICT, "ACPP_TICKET_NOT_WRITABLE", "本棒当前不可写入（已领租约或已完成）"),
    ACPP_WRONG_AGENT(HttpStatus.FORBIDDEN, "ACPP_WRONG_AGENT", "这一棒属于另一个机娘，请换回原机娘"),
    ACPP_LEASE_ALREADY_CLAIMED(HttpStatus.CONFLICT, "ACPP_LEASE_ALREADY_CLAIMED", "本棒的租约已被领走，请查询席位状态"),
    ACPP_LEASE_EXPIRED(HttpStatus.GONE, "ACPP_LEASE_EXPIRED", "租约已过期，请向主人申请重试（retry）"),
    ACPP_LEASE_INVALID(HttpStatus.FORBIDDEN, "ACPP_LEASE_INVALID", "租约令牌无效，请停止并报告主人"),

    // —— 幂等（L16 · Idempotency-Key）——
    // 两条都是 409：请求本身没错，是【和你自己先前那次请求】打架。
    //   IN_PROGRESS  → 同 key 同内容，上一次还在跑 → 稍后原样重试即可（是安全的）
    //   REUSED       → 同 key 不同内容 → 客户端把一个 key 用在了两件事上，必须换新 key
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
            "同一 Idempotency-Key 的请求正在处理中，请稍后重试"),
    IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY",
            "同一 Idempotency-Key 被用于不同的请求内容，请换一个新的 key"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
            "本端点要求携带 Idempotency-Key 请求头"),

    // —— 接口限流（L17 Redis 令牌桶）——
    // 429 Too Many Requests：同一个机娘在短时间内请求次数超过额度。
    // 判据：这个计数错了【不会】破坏任何不变量（多放几次没有数据后果）→ 放在 Redis 令牌桶。
    // 对比：登录锁定（LOGIN_LOCKED 429）是安全不变量（多放一次 = 攻击者多一次猜密码机会）→ 放在 MySQL。
    // ★ 三种 429 的分工（本项目特色）：
    //   CODE_SEND_TOO_FREQUENT / LOGIN_LOCKED → 安全/成本不变量，MySQL 守
    //   RATE_LIMIT_EXCEEDED                   → 容量保护，Redis 令牌桶（可以不准）
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
            "请求频率超过限额，请稍后重试"),
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
