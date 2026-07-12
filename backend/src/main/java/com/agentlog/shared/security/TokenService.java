package com.agentlog.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Opaque Token 生成与摘要（L12 起，CLI/Agent 双轨认证的安全基石）。
 *
 * 做两件事：
 *   1) 生成明文 token：前缀 + base64url(32 随机字节)。如 owner_at_xxx、device_xxx。明文只回客户端一次。
 *   2) 算摘要：HMAC-SHA256(pepper, 明文token) → 32 字节。库里只存摘要（BINARY(32)）。
 *
 * 为什么不存明文 token：脱库时攻击者拿到的只是摘要，没服务端 pepper 算不出明文，无法冒用。
 *   与密码存 bcrypt 同理——可验证、不可逆推。
 * 为什么用 HMAC 而非纯 SHA-256：HMAC 带密钥（pepper），防「彩虹表/预计算撞库」——
 *   纯哈希短 token 可能被预计算，HMAC 没 pepper 无从下手。
 *
 * 校验 token（L13 的 bearer 过滤器用）：拿客户端传来的明文，用同样 pepper 算摘要，比对库里的摘要。
 */
@Service
@EnableConfigurationProperties(TokenProperties.class)
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final TokenProperties props;

    public TokenService(TokenProperties props) {
        this.props = props;
    }

    /** 生成明文 token：prefix + base64url(32 随机字节)。如 generateRawToken("device_")。 */
    public String generateRawToken(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + URL_ENCODER.encodeToString(bytes);
    }

    /** 算 HMAC-SHA256(pepper, 明文token) → 32 字节摘要（存库）。 */
    public byte[] digest(String rawToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("token 摘要计算失败", e);
        }
    }
}
