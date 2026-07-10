package com.agentlog.identity.application;

import com.agentlog.identity.domain.EmailRules;
import com.agentlog.shared.error.ApiException;
import com.agentlog.shared.error.ApiStatus;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * L11.5 邮箱验证码服务：生成 → 发邮件 → 存 Redis → 校验。对照旧论坛 springBootDemo 的 EmailService。
 *
 * 【为什么用 Redis 存验证码】短命(10min)、高频、带过期——塞 MySQL 又要建表又要清理；
 *   Redis 一个 SET key val EX 600 就带过期，读写还快。验证码值就是字符串，直接用框架自带
 *   StringRedisTemplate（比旧项目自定义 RedisTemplate<String,Object>+Json 更透明，零配置零魔法）。
 *
 * 【邮件优雅降级】没配 SMTP（AGENTLOG_MAIL_USERNAME 为空）或发送失败时，只把验证码打进日志，
 *   不阻断注册流程——方便本地/测试跑通全链路，配了真账号就真发邮件。
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** 注册验证码 Redis key 前缀。key = 前缀 + email。 */
    private static final String CODE_KEY_PREFIX = "auth:register:code:";
    /** 验证码有效期 10 分钟。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    /** 60秒防刷间隔 */
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final JavaMailSender mailSender;
    private final String fromEmail;

    public EmailVerificationService(
            StringRedisTemplate redis,
            JavaMailSender mailSender,
            @Value("${spring.mail.username:}") String fromEmail) {
        this.redis = redis;
        this.mailSender = mailSender;
        this.fromEmail = fromEmail;
    }

    /** 发注册验证码：防刷 → 生成 → 存 Redis → 发邮件（失败降级打日志）。 */
    public void issueRegisterCode(String email) {
        // 兜底校验（HTTP 入口已有 @Pattern，这里防住不走 Controller 的调用方）。正则同 EmailRules。
        if (email == null || !email.matches(EmailRules.EMAIL_REGEX)) {
            throw new ApiException(ApiStatus.EMAIL_INVALID);
        }
        String key = CODE_KEY_PREFIX + email;
        // 防刷：距离上一条验证码还没过60s就再发 → 拒绝（60s 内一封）。
        Long remainTtl = redis.getExpire(key, TimeUnit.SECONDS);
        if (remainTtl != null && remainTtl > 0 && remainTtl > (CODE_TTL.getSeconds() - COOLDOWN.getSeconds())) {
            throw new ApiException(ApiStatus.CODE_SEND_TOO_FREQUENT);
        }
        String code = generateCode();
        redis.opsForValue().set(key, code, CODE_TTL);
        sendOrLog(email, code);
    }

    /** 校验注册验证码：过期 / 错误 各自报错；通过即删（一次性，防重放）。 */
    public void verifyRegisterCode(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        String stored = redis.opsForValue().get(key);
        if (stored == null) {
            throw new ApiException(ApiStatus.VERIFICATION_CODE_EXPIRED);
        }
        if (!stored.equals(code)) {
            throw new ApiException(ApiStatus.VERIFICATION_CODE_INVALID);
        }
        redis.delete(key);
    }

    private String generateCode() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000)); // 6 位
    }

    private void sendOrLog(String email, String code) {
        // 没配发件邮箱 → 开发/测试降级：只打日志，全链路仍可测。
        if (fromEmail == null || fromEmail.isBlank()) {
            log.info("【DEV·未配 SMTP】注册验证码 {} → {}（10 分钟内有效）", code, email);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("AgentLog <" + fromEmail + ">"); // 发件人必须是 spring.mail.username 本身
            message.setTo(email);
            message.setSubject("【AgentLog】您的注册验证码");
            message.setText("欢迎注册 AgentLog！\n\n您的验证码是：" + code
                    + "\n\n有效期 10 分钟，请勿泄露给他人。若非本人操作请忽略。");
            mailSender.send(message);
            log.info("注册验证码邮件已发送 → {}", email);
        } catch (Exception e) {
            // 发送失败不阻断注册：降级打日志（含验证码，便于排障/本地验收）。
            log.error("验证码邮件发送失败 → {}，降级：验证码 {}，原因：{}", email, code, e.getMessage());
        }
    }
}
