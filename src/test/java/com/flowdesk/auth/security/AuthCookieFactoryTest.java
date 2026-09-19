package com.flowdesk.auth.security;

import com.flowdesk.auth.config.AuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthCookieFactoryTest {

    private static final String COOKIE_NAME = "FLOWDESK_REFRESH";
    private static final String COOKIE_PATH = "/fd/v1/auth";

    private final AuthCookieFactory factory = new AuthCookieFactory(properties(false));

    @Test
    void refreshCookieCarriesTheSecurityFlags() {
        ResponseCookie cookie = factory.refreshTokenCookie("raw-token");

        assertThat(cookie.getName()).isEqualTo(COOKIE_NAME);
        assertThat(cookie.getValue()).isEqualTo("raw-token");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
        assertThat(cookie.getPath()).isEqualTo(COOKIE_PATH);
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(7));
        assertThat(cookie.isSecure()).isFalse();
    }

    @Test
    void cookieIsSecureWhenConfigured() {
        ResponseCookie cookie = new AuthCookieFactory(properties(true)).refreshTokenCookie("raw-token");

        assertThat(cookie.isSecure()).isTrue();
    }

    @Test
    void clearedCookieIsEmptyAndExpiresImmediately() {
        ResponseCookie cookie = factory.clearedRefreshTokenCookie();

        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getPath()).isEqualTo(COOKIE_PATH);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Strict");
    }

    private static AuthProperties properties(boolean cookieSecure) {
        return new AuthProperties(Duration.ofDays(7), 32, COOKIE_NAME, COOKIE_PATH, cookieSecure,
                List.of(cookieSecure ? "https://localhost" : "http://localhost"));
    }
}
