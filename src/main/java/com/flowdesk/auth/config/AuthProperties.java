package com.flowdesk.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 登录会话和 Refresh Cookie 的类型安全配置。 */
@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    @NotNull
    private Duration refreshExpiration = Duration.ofDays(7);

    @NotBlank
    private String refreshCookieName = "FLOWDESK_REFRESH";

    @NotBlank
    private String refreshCookiePath = "/fd/v1/auth";

    private boolean cookieSecure;

    @Min(32)
    private int refreshTokenBytes = 32;

    public Duration getRefreshExpiration() {
        return refreshExpiration;
    }

    public void setRefreshExpiration(Duration refreshExpiration) {
        this.refreshExpiration = refreshExpiration;
    }

    public String getRefreshCookieName() {
        return refreshCookieName;
    }

    public void setRefreshCookieName(String refreshCookieName) {
        this.refreshCookieName = refreshCookieName;
    }

    public String getRefreshCookiePath() {
        return refreshCookiePath;
    }

    public void setRefreshCookiePath(String refreshCookiePath) {
        this.refreshCookiePath = refreshCookiePath;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }

    public int getRefreshTokenBytes() {
        return refreshTokenBytes;
    }

    public void setRefreshTokenBytes(int refreshTokenBytes) {
        this.refreshTokenBytes = refreshTokenBytes;
    }
}
