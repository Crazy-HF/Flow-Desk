package com.flowdesk.shared.utils;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtils {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final long expirationSeconds;
    private final String issuer;
    private final Clock clock;

    public JwtUtils(JwtProperties properties, Clock clock) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.getSecret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT secret must be valid Base64", exception);
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("HS256 secret must be at least 256 bits (32 bytes)");
        }
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");

        // 编码器：用对称密钥构造 JWKSource，兼容所有 Spring Security 版本
        this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));

        // 解码器：显式指定 HS256，避免密钥长度导致的算法自动升级
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        this.expirationSeconds = properties.getExpiration();
        this.issuer = properties.getIssuer();
        this.clock = clock;
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        this.jwtDecoder = decoder;
    }

    /**
     * 签发登录所需的 Access Token。角色带 ROLE_ 前缀的转换由 SecurityFilterChain 负责。
     */
    public String generateAccessToken(
            Long userId,
            String username,
            String sessionId) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("username", username);
        claims.put("sid", sessionId);
        return generateToken(String.valueOf(userId), claims);
    }

    /**
     * 签发 HS256 JWT
     */
    private String generateToken(String subject, Map<String, Object> extraClaims) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer(issuer)
                .id(UUID.randomUUID().toString())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(expirationSeconds));

        if (extraClaims != null) {
            extraClaims.forEach(claimsBuilder::claim);
        }

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtEncoderParameters params = JwtEncoderParameters.from(jwsHeader, claimsBuilder.build());
        return jwtEncoder.encode(params).getTokenValue();
    }

    /**
     * 完整校验：签名 + 过期时间 + issuer（+ nbf 等默认校验）
     */
    public Jwt parseAndValidate(String token) {
        return jwtDecoder.decode(token);
    }

    /** 供 Spring Security Resource Server 复用同一套密钥和校验规则。 */
    public JwtDecoder decoder() {
        return jwtDecoder;
    }

    /** Access Token 有效期，单位为秒。 */
    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    /**
     * 从 token 字符串中提取用户 ID（subject）。
     * 注意：会完整校验签名与有效期。
     */
    public String getUserId(String token) {
        return parseAndValidate(token).getSubject();
    }

    /** 从已校验 Token 中取得 Redis 会话标识。 */
    public String getSessionId(String token) {
        return getSessionId(parseAndValidate(token));
    }

    /** 从 Spring Security 已校验的 Jwt 中取得 Redis 会话标识。 */
    public String getSessionId(Jwt jwt) {
        return jwt.getClaimAsString("sid");
    }

    /**
     * 从当前请求的 SecurityContext 中获取用户 ID。
     * 适用于 Controller / Service 层，无需再手动传 token。
     * 依赖 SecurityFilterChain 中已配置 oauth2ResourceServer().jwt()
     */
    public static String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        // 兼容 @AuthenticationPrincipal Jwt 注入的场景
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }
}
