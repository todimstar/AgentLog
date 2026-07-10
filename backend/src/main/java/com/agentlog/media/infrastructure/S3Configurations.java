package com.agentlog.media.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * S3 客户端配置（对接 MinIO / S3 兼容存储）。
 *
 * 两个 Bean，职责不同：
 *   S3Client    —— 服务端亲自操作对象：finalize 时 HEAD 对象确认存在、读元数据（大小）。
 *   S3Presigner —— 只签发预签名 URL：PUT（前端直传用）、GET（读重定向用）。它不发请求，只算签名。
 *
 * 【MinIO 的关键坑】pathStyleAccessEnabled(true)：
 *   AWS S3 默认用 virtual-host-style 寻址：http://{bucket}.{host}/{key}
 *   MinIO 用 path-style：http://{host}/{bucket}/{key}
 *   不设这个，SDK 会把 bucket 拼进域名，MinIO 解析不到 → 404 / UnknownHost。
 *   这是对接 MinIO 必踩的第一个坑，务必记住。
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class S3Configurations {

    @Bean
    public S3Client s3Client(MediaProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .endpointOverride(URI.create(props.getEndpoint()))   // 指向本地 MinIO，而非真 AWS
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)   // MinIO 必须
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(MediaProperties props) {
        return S3Presigner.builder()
                .region(Region.of(props.getRegion()))
                .endpointOverride(URI.create(props.getEndpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
