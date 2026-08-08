package com.agentlog.collaboration.infrastructure.persistence.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * contribution_attempt 表的 DO —— 一个席位上的一次写作过程，租约内嵌于此（V013）。
 *
 * ★ 为什么这一课才需要它：L14 的投稿、L15 的接力都是<b>瞬时动作</b>（一个请求 200ms 做完）；
 *   而 L16 里机娘先说「该我了」，然后去读上一棒、思考、生成几百字，<b>几分钟后</b>才回来提交。
 *   第一次出现了「一段进行中的时间」，就必须有东西来承载它。
 *
 * ★ 租约不是锁：锁需要有人来解，而机娘的对话可能崩掉、再也不回来 → 队列永久卡死。
 *   租约是<b>有到期时间的独占权</b>，到点自动失效，不需要任何人来解。
 *
 * ⚠️ 本 DO <b>没有</b> @Version，也不该用 updateById 改 status：
 *   状态推进只走 ContributionAttemptMapper.xml 里的条件 UPDATE（闸门），
 *   那条 SQL 自己在 WHERE 里做判定。留个 setter 让人 updateById，就等于给「查-判-改」三步走开后门。
 *   （同 {@link HandoffTokenDO} 的处置思路：把"只能这么改"体现在类型上，比写在注释里可靠。）
 */
@TableName("contribution_attempt")
public class ContributionAttemptDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 哪个席位（contribution_ticket.id）。 */
    private Long ticketId;

    /** 这张票的第几次尝试（1 起）。L16 恒为 1；retry（L18）才有 2、3…… */
    private Integer attemptNo;

    private String status;

    // ↓↓↓ 内嵌的租约五列 ↓↓↓

    /**
     * HMAC-SHA256(pepper, 明文) 的 32 字节摘要。
     * ★ 与 handoff 令牌的关键差别：handoff 必须经过主人的手复制粘贴到另一个 AI 对话，所以要回显明文；
     *   lease 不经过人（同一个 CLI 进程领了、自己用），回显只增加泄漏面 —— 故 CLI 规范明写「不输出 lease token」。
     *   同样是一次性令牌，可见性策略由<b>传递路径</b>决定。
     */
    private byte[] leaseTokenDigest;

    private Instant leaseIssuedAt;

    /**
     * ★ 本课最重要的一列。过期判定写进 submit 那条 UPDATE 的 WHERE 做<b>惰性执行</b>：
     * 即使超时的租约还挂着 ACTIVE（L17 的 Worker 还没来标记），它也一定提交不进来。
     * 这就是本课能在「禁止写 Worker」的约束下依然安全的全部原因。
     */
    private Instant leaseExpiresAt;

    /** 心跳（本课建列不用，L17 续租用）。 */
    private Instant lastHeartbeatAt;

    /** 续租上限，防无限续租（本课建列不用，L18 用）。 */
    private Instant maxLeaseExpiresAt;

    /** 失败原因指针。error_report 表是 L17 建的，故本列建了但无外键。 */
    private Long errorReportId;

    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(Integer attemptNo) {
        this.attemptNo = attemptNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public byte[] getLeaseTokenDigest() {
        return leaseTokenDigest;
    }

    public void setLeaseTokenDigest(byte[] leaseTokenDigest) {
        this.leaseTokenDigest = leaseTokenDigest;
    }

    public Instant getLeaseIssuedAt() {
        return leaseIssuedAt;
    }

    public void setLeaseIssuedAt(Instant leaseIssuedAt) {
        this.leaseIssuedAt = leaseIssuedAt;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getMaxLeaseExpiresAt() {
        return maxLeaseExpiresAt;
    }

    public void setMaxLeaseExpiresAt(Instant maxLeaseExpiresAt) {
        this.maxLeaseExpiresAt = maxLeaseExpiresAt;
    }

    public Long getErrorReportId() {
        return errorReportId;
    }

    public void setErrorReportId(Long errorReportId) {
        this.errorReportId = errorReportId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
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
