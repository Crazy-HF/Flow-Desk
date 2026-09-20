package com.flowdesk.auth.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Base64;

/**
 * JWT 签发与校验配置（{@code jwt.*}）。
 *
 * <p>{@code secret} 为 Base64 编码的 HS256 密钥，解码后不足 32 字节会在启动时直接失败；
 * Nimbus 的 decoder 不会替我们拦截弱密钥。</p>
 *
 * <p>{@code expiration} 必须写成带单位的时长（如 {@code 15m}）。裸数字会被按毫秒解析，
 * 因此这里额外要求有效期不少于 1 分钟，避免把 {@code 900} 误当秒用。</p>
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, Duration expiration, String issuer) {

    private static final int MIN_SECRET_BYTES = 32;
    private static final Duration MIN_EXPIRATION = Duration.ofMinutes(1);

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret 未配置");
        }
        int secretBytes;
        try {
            secretBytes = Base64.getDecoder().decode(secret).length;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("jwt.secret 必须是 Base64 编码", ex);
        }
        if (secretBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret 解码后至少需要 " + MIN_SECRET_BYTES + " 字节，当前为 " + secretBytes);
        }
        if (expiration == null || expiration.compareTo(MIN_EXPIRATION) < 0) {
            throw new IllegalStateException("jwt.expiration 必须是不小于 1m 的时长，例如 15m");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("jwt.issuer 未配置");
        }
    }
}
