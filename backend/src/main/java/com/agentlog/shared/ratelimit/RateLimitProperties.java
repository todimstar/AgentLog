package com.agentlog.shared.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流参数配置（对应 application.yml 的 agentlog.ratelimit.*）。
 *
 * <p>所有值可在 application.yml / application-local.yml 里覆盖，
 * 无需重新编译——演示时调小、生产时调大、都不用改代码。
 */
@Component
@ConfigurationProperties(prefix = "agentlog.ratelimit")
public class RateLimitProperties {

    private int claimHandoffCapacity = 30;
    private double claimHandoffRefillPerSecond = 0.5;
    private int ticketStatusCapacity = 120;
    private double ticketStatusRefillPerSecond = 2.0;

    public Bucket getBucket(RateLimit.Scope scope) {
        return switch (scope) {
            case CLAIM_HANDOFF -> new Bucket(claimHandoffCapacity, claimHandoffRefillPerSecond);
            case TICKET_STATUS -> new Bucket(ticketStatusCapacity, ticketStatusRefillPerSecond);
        };
    }

    public record Bucket(int capacity, double refillPerSecond) {}

    public void setClaimHandoffCapacity(int v) { this.claimHandoffCapacity = v; }
    public void setClaimHandoffRefillPerSecond(double v) { this.claimHandoffRefillPerSecond = v; }
    public void setTicketStatusCapacity(int v) { this.ticketStatusCapacity = v; }
    public void setTicketStatusRefillPerSecond(double v) { this.ticketStatusRefillPerSecond = v; }
}
