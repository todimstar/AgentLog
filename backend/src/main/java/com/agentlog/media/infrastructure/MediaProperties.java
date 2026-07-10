package com.agentlog.media.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 媒体存储配置（绑定 application.yml 的 agentlog.media.*）。
 *
 * 对接 S3 兼容存储（本地 MinIO，生产可换真 S3）。所有可调参数集中在这里，
 * 不散落在代码里——切换 endpoint/bucket/TTL 改配置即可，不动代码（12-Factor 配置外置）。
 */
@ConfigurationProperties(prefix = "agentlog.media")
public class MediaProperties {

    private String bucket = "agentlog-private";
    private String endpoint = "http://localhost:9000";
    private String region = "us-east-1";
    private String accessKey;
    private String secretKey;
    private Duration presignedPutTtl = Duration.ofMinutes(5);   // 上传通行证有效期（短，够传就行）
    private Duration presignedGetTtl = Duration.ofMinutes(15);  // 读通行证有效期
    private long maxImageSizeBytes = 5_242_880L;                // 5 MB
    private int maxImagesPerPost = 9;

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public Duration getPresignedPutTtl() { return presignedPutTtl; }
    public void setPresignedPutTtl(Duration presignedPutTtl) { this.presignedPutTtl = presignedPutTtl; }

    public Duration getPresignedGetTtl() { return presignedGetTtl; }
    public void setPresignedGetTtl(Duration presignedGetTtl) { this.presignedGetTtl = presignedGetTtl; }

    public long getMaxImageSizeBytes() { return maxImageSizeBytes; }
    public void setMaxImageSizeBytes(long maxImageSizeBytes) { this.maxImageSizeBytes = maxImageSizeBytes; }

    public int getMaxImagesPerPost() { return maxImagesPerPost; }
    public void setMaxImagesPerPost(int maxImagesPerPost) { this.maxImagesPerPost = maxImagesPerPost; }
}
