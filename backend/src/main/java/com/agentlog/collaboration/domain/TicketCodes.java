package com.agentlog.collaboration.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 对外标识码生成：post_ticket（PT-）与 ticket_code（CT-）。
 *
 * ★ 为什么随机、不用自增序号——这是一个安全决策，不是审美：
 *   `ticket_code` 会出现在 URL 路径里（L16 的 `GET /agent/contribution-tickets/{ticketCode}`）。
 *   若用 `CT-0001`/`CT-0002` 这种递增码，任何持有效 agent 令牌的人都能顺着数字往下扫，
 *   探测「系统里一共有多少票、别的主人在写什么」——即使每次都被行级授权拦成 404，
 *   **响应时间与错误分布本身就是信息**。
 *   Pack `09-security/多租户授权与行级隔离.md §5` 的原则是「跨 owner 访问默认返回 404，避免资源枚举」；
 *   随机码是把枚举面**从根上消除**，而不是靠授权层逐个拦——同 V012 里「不建空草稿」的思路：
 *   能用数据形态消除的风险，不要留给运行时检查。
 *   （Pack demo 脚本里的 `CT-...`/`CT-0002` 是示意写法，不是编码规范。）
 *
 * ★ 为什么不复用 TokenService.generateRawToken()：
 *   那个生成的是 32 字节 base64url（43 字符）的**凭证**——它的长度是为了抗暴力猜测，
 *   因为持有即授权。而 ticketCode 是**标识符**不是凭证（拿到它并不能操作，还要过令牌与行级授权），
 *   8 字节（64 bit）随机足够避免碰撞与枚举，短一半、可读性好得多。
 *   把「凭证」和「标识符」的强度要求分开，是刻意的：不要因为"更长更安全"就无脑加长标识符。
 */
public final class TicketCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    /** 随机字节数。8 字节 = 64 bit = 16 位 hex，碰撞概率可忽略（且有 UNIQUE 键兜底）。 */
    private static final int RANDOM_BYTES = 8;

    private TicketCodes() {
    }

    /** 生成协作会话对外标识，形如 {@code PT-3f9a1c07b4e2d581}。 */
    public static String newPostTicket() {
        return "PT-" + randomHex();
    }

    /** 生成贡献席位对外标识，形如 {@code CT-8b21e0d4a7c93f60}。 */
    public static String newTicketCode() {
        return "CT-" + randomHex();
    }

    private static String randomHex() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
