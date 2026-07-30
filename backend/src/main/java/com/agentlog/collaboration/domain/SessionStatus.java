package com.agentlog.collaboration.domain;

/**
 * 协作会话状态字典。对应 collaboration_session.status 列（V012，VARCHAR + ck_collab_status CHECK 约束）。
 *
 * 用法同 content 模块的 DraftStatus（做法A·常量字典）：DO 的 status 保持 String，
 * 业务代码用本枚举当"合法选项集"，存库 setStatus(SessionStatus.OPEN.getCode())。
 * 好处：合法值集中在此、IDE 可补全、写错即编译错；又不引入 MyBatis 枚举转换的魔法层。
 * 数据库那边还有 ck_collab_status CHECK 兜底——Java 侧写错常量至少能编译期挡住，
 * 绕过 Java 直接写库（如测试里 JdbcTemplate 直插）则由 CHECK 挡住。两道防线方向不同。
 *
 * ★ 状态机全景（Pack 10-reliability/ACPP状态机.md）：
 * <pre>
 *   [*] → OPEN                                    start（L15，本课唯一会产生的状态）
 *   OPEN → RUNNING                                首棒领租约（L16）
 *   RUNNING → AWAITING_CONTINUATION               当前棒完成、无下一棒在跑（L16）
 *   AWAITING_CONTINUATION → RUNNING               下一棒继续（L16）
 *   RUNNING → PAUSED_ON_ERROR                     中间棒失败（L17）
 *   PAUSED_ON_ERROR → RUNNING                     主人 retry（L18）
 *   OPEN → INVALIDATED                            首棒就失败（L17）★ 此时 post/draft 从未创建 → 不暴露空草稿
 *   AWAITING_CONTINUATION → READY_FOR_OWNER_REVIEW 主人停止接力（L18）
 *   PAUSED_ON_ERROR → TERMINATED                  主人终止（L18）
 *   READY_FOR_OWNER_REVIEW → PUBLISHED            主人批准发布（L20）
 * </pre>
 * L15 只走到第一行——本课不做 lease/submit/worker/retry，所以 session 建出来就停在 OPEN。
 */
public enum SessionStatus {

    /** 已开局，首棒尚未领租约。L15 的终点。 */
    OPEN("OPEN"),

    /** 有一棒正在写（L16 首棒 claim lease 时进入）。 */
    RUNNING("RUNNING"),

    /** 当前棒完成、没有下一棒在跑——等着有人拿尾令牌接力（L16）。 */
    AWAITING_CONTINUATION("AWAITING_CONTINUATION"),

    /** 中间棒失败，后序被阻塞，等主人 retry（L17）。 */
    PAUSED_ON_ERROR("PAUSED_ON_ERROR"),

    /** 首棒就失败 → 整个会话作废。此时 post/draft 从未创建，故不会留下空草稿（L17）。 */
    INVALIDATED("INVALIDATED"),

    /** 主人停止接力，草稿交审稿（L18/L20）。 */
    READY_FOR_OWNER_REVIEW("READY_FOR_OWNER_REVIEW"),

    /** 主人终止协作（L18）。 */
    TERMINATED("TERMINATED"),

    /** 主人批准并发布（L20）。 */
    PUBLISHED("PUBLISHED");

    private final String code;

    SessionStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
