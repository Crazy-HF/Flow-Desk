package com.flowdesk.auth.security;

import com.flowdesk.auth.config.JwtProperties;
import com.flowdesk.auth.domain.AccessToken;
import com.flowdesk.auth.domain.AuthClaims;
import com.flowdesk.common.exception.ApiException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * HS256 Access Token 的签发与校验。
 *
 * <p>令牌只携带用户标识、会话标识和标准时间声明；角色与权限不进入令牌，
 * 由请求认证阶段从 Redis 会话快照注入。</p>
 */
@Component
public class JwtTokenService {

    private static final String SESSION_ID_CLAIM = "sid";

    private final JwtProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    /**
     * 创建 JWT 签发与校验服务。
     */
    public JwtTokenService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        SecretKey key = new SecretKeySpec(
                Base64.getDecoder().decode(properties.secret()), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        this.decoder = decoder;
    }

    /** 为指定用户和会话签发 Access Token。 */
    public AccessToken issue(long userId, String sessionId) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.expiration());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(Long.toString(userId))
                .claim(SESSION_ID_CLAIM, sessionId)
                .build();
        Jwt jwt = encoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        return new AccessToken(jwt.getTokenValue(), expiresAt);
    }

    /**
     * 校验签名、有效期与 issuer，并取出会话身份。
     *
     * @throws ApiException 令牌缺失、无法验证或缺少必要声明时返回 {@code 401 / AUTH_REQUIRED}
     */
    public AuthClaims parse(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) {
            throw unauthorized();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(tokenValue);
        } catch (JwtException ex) {
            // 签名错误、已过期、issuer 不匹配对外都只表示"令牌不可用"，具体原因只应进入审计日志
            throw unauthorized();
        }
        String sessionId = jwt.getClaimAsString(SESSION_ID_CLAIM);
        if (sessionId == null || sessionId.isBlank()) {
            throw unauthorized();
        }
        try {
            return new AuthClaims(Long.parseLong(jwt.getSubject()), sessionId, jwt.getExpiresAt());
        } catch (NumberFormatException ex) {
            throw unauthorized();
        }
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "未提供或无法验证 Access Token");
    }
}