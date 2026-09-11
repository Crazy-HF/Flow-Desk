package com.flowdesk.shared.utils;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilsTest {

    @Test
    void generatesHs256AccessTokenWithExpectedClaimsAndLifetime() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        JwtUtils jwtUtils = new JwtUtils(properties(validSecret(), 900, "https://flowdesk.test"),
                Clock.fixed(now, ZoneOffset.UTC));

        String token = jwtUtils.generateAccessToken(
                42L,
                "alice",
                "session-123"
        );
        Jwt jwt = jwtUtils.parseAndValidate(token);

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://flowdesk.test");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(900));
        assertThat(jwt.getClaimAsString("username")).isEqualTo("alice");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo("session-123");
        assertThat(jwt.getClaims()).doesNotContainKeys("roles", "permissions");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwtUtils.getSessionId(token)).isEqualTo("session-123");
        assertThat(jwtUtils.getExpirationSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsSecretShorterThan256BitsAfterBase64Decoding() {
        String shortSecret = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new JwtUtils(
                properties(shortSecret, 900, "https://flowdesk.test"),
                Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("256 bits");
    }

    private JwtProperties properties(String secret, long expiration, String issuer) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpiration(expiration);
        properties.setIssuer(issuer);
        return properties;
    }

    private String validSecret() {
        byte[] bytes = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
