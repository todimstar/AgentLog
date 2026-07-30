package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * handoff_token 表的 DO —— 一次性的「下一棒资格」凭证（V012）。
 *
 * ★ 明文与摘要：tokenDigest 存的是 HMAC-SHA256(serverPepper, 明文)。明文只在 HTTP 响应里出现一次
 *   （必须出现——否则主人没东西可粘贴给下一个 AI），之后服务端不再持有。
 *   与 CLI 的 credentials.json 明文存盘对比：两侧的【爆炸半径】差几个数量级——
 *   服务端脱库 = 全部用户全部令牌；客户端泄漏 = 一个用户一台机器。
 *   所以本表的安全模型不是「绝不泄漏」，而是「泄漏了很快贬值」：24h TTL + 一次性消费。
 *
 * ⚠️ 注意本 DO **没有** @Version：
 *   本表的状态推进【只走 HandoffTokenMapper.xml 的原子 UPDATE】，那条 SQL 自己写 version = version + 1。
 *   若在此标 @Version，会让人误以为可以用 updateById 改状态——那正是本课要避免的「查-判-改」三步走。
 *   把"只能这么改"体现在类型上，比写在注释里更可靠（同 MarkdownContent 组件不给调用方 HTML 字符串的思路）。
 */
@TableName("handoff_token")
public class HandoffTokenDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** HMAC-SHA256(pepper, 明文) 的 32 字节摘要。既是唯一键也是查找键。 */
    private byte[] tokenDigest;

    private Long sessionId;

    /** 冗余存主人（== session.owner_user_id）：消费时一条查询就能做行级授权，不必 join 回 session。 */
    private Long ownerUserId;

    /** 我是「哪一棒之后」的资格。消费我建出的新票，predecessor 就是它。 */
    private Long predecessorTicketId;

    private String status;

    /** 24h（agentlog.token.handoff-ttl）。过期判定写进原子 UPDATE 的 WHERE，不依赖 Worker。 */
    private Instant expiresAt;

    private Long consumedByAgentId;
    private Long consumedTicketId;
    private Instant consumedAt;

    private Long version;

    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public byte[] getTokenDigest() {
        return tokenDigest;
    }

    public void setTokenDigest(byte[] tokenDigest) {
        this.tokenDigest = tokenDigest;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getPredecessorTicketId() {
        return predecessorTicketId;
    }

    public void setPredecessorTicketId(Long predecessorTicketId) {
        this.predecessorTicketId = predecessorTicketId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getConsumedByAgentId() {
        return consumedByAgentId;
    }

    public void setConsumedByAgentId(Long consumedByAgentId) {
        this.consumedByAgentId = consumedByAgentId;
    }

    public Long getConsumedTicketId() {
        return consumedTicketId;
    }

    public void setConsumedTicketId(Long consumedTicketId) {
        this.consumedTicketId = consumedTicketId;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
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
}
