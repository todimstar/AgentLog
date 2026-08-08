package com.agentlog.shared.idempotency;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

/**
 * idempotency_record 表的 DO —— 幂等台账（V013）。
 *
 * 一张流水账：记「这个 key 我处理过没有、处理成什么样了」。
 * 重复请求来了查账即可，不必重新执行业务。
 */
@TableName("idempotency_record")
public class IdempotencyRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 幂等域按主人隔离（多租户铁律）。 */
    private Long ownerUserId;

    /** 再按机娘隔离：两个机娘各自的 key 互不干扰。 */
    private Long agentId;

    /**
     * ★ 存<b>路由模板</b>而不是实际 URI，如
     * {@code POST /api/v1/agent/contribution-tickets/{ticketCode}/leases}。
     *
     * 实际 URI 含 ticketCode，会把同一个 key 在不同票上的使用切成两个幂等域 ——
     * 那样「同 key 用在两件事上」就悄悄变成合法了，反而破坏幂等语义。
     * 路径参数改为参与 requestHash：同 key 不同票 → hash 不同 → 409 提示换新 key。
     */
    private String endpoint;

    /** 客户端生成的随机串（Idempotency-Key 头）。 */
    private String idempotencyKey;

    /** SHA-256(方法参数 JSON) 的 64 位十六进制。用于识别「同 key 却是不同请求」。 */
    private String requestHash;

    private String status;

    /** 完成时缓存的 HTTP 状态码（记录用途；重放走同一个 Controller 方法签名，@ResponseStatus 自然一致）。 */
    private Integer responseStatus;

    /** 完成时缓存的响应体 JSON。重放时反序列化后原样返回，<b>业务一行都不执行</b>。 */
    private String responseBodyJson;

    /** 默认 24h。纯清理用途（L17 Worker），<b>不参与任何判定</b>。 */
    private Instant expiresAt;

    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Long ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBodyJson() {
        return responseBodyJson;
    }

    public void setResponseBodyJson(String responseBodyJson) {
        this.responseBodyJson = responseBodyJson;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
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

    /** 台账状态。只有两个值：抢占中、已完成。 */
    public enum Status {

        /**
         * 已抢占、业务执行中。
         * ★ 这个状态必须对并发者<b>立刻可见</b>，所以台账写入走 REQUIRES_NEW 独立事务 ——
         * 藏在未提交的事务里，它就等于不存在。
         */
        IN_PROGRESS("IN_PROGRESS"),

        /** 业务成功，响应已缓存。重放时直接返回。 */
        COMPLETED("COMPLETED");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
