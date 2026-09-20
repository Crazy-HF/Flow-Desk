package com.flowdesk.auth.security;

import com.flowdesk.auth.config.JwtProperties;
import com.flowdesk.auth.domain.AccessToken;
import com.flowdesk.auth.domain.AuthClaims;
import com.flowdesk.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {

    private static final String SECRET = secret("01234567890123456789012345678901");
    private static final String OTHER_SECRET = secret("another-secret-that-is-32-bytes!");
    private static final String ISSUER = "https://flowdesk.test";

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final JwtTokenService service = new JwtTokenService(properties(ISSUER, SECRET), clock);

    @Test
    void issuedTokenCarriesUserSessionAndExpiry() {
        AccessToken token = service.issue(42L, "session-1");

        AuthClaims claims = service.parse(token.value());

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.sessionId()).isEqualTo("session-1");
        assertThat(claims.expiresAt()).isEqualTo(token.expiresAt());
        assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(token.value().split("\\.")).hasSize(3);      // header.payload.signature
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service.issue(42L, "session-1").value();
        String tampered = token.substring(0, token.length() - 2) + "xy";

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo("AUTH_REQUIRED"));
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = new JwtTokenService(properties(ISSUER, OTHER_SECRET), clock)
                .issue(42L, "session-1").value();

        assertThatThrownBy(() -> service.parse(foreign)).isInstanceOf(ApiException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String foreign = new JwtTokenService(properties("https://evil.test", SECRET), clock)
                .issue(42L, "session-1").value();

        assertThatThrownBy(() -> service.parse(foreign)).isInstanceOf(ApiException.class);
    }

    /** 有效期由真实时钟校验，所以用两小时前的时钟签发即可得到过期令牌。 */
    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String expired = new JwtTokenService(properties(ISSUER, SECRET), past).issue(42L, "session-1").value();

        assertThatThrownBy(() -> service.parse(expired)).isInstanceOf(ApiException.class);
    }

    @Test
    void missingOrBrokenInputIsRejected() {
        assertThatThrownBy(() -> service.parse(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.parse("   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.parse("not-a-jwt")).isInstanceOf(ApiException.class);
    }

    private static String secret(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static JwtProperties properties(String issuer, String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(15), issuer);
    }
}
