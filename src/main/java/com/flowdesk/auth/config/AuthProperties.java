package com.flowdesk.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * 认证会话与 Refresh Cookie 配置（{@code auth.*}）。
 *
 * <p>只暴露"随环境变化"的值；HttpOnly 与 SameSite=Strict 属于安全策略，固定写在 Cookie 组装代码里。</p>
 *
 * <p>来源白名单在绑定阶段归一化为小写并拒绝通配符、路径和结尾斜杠：浏览器发送的 Origin
 * 形如 {@code scheme://host[:port]}，带结尾斜杠的配置永远不会匹配。</p>
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        Duration refreshExpiration,
        int refreshTokenBytes,
        String refreshCookieName,
        String refreshCookiePath,
        boolean cookieSecure,
        List<String> allowedOrigins) {

    private static final Duration MIN_REFRESH_EXPIRATION = Duration.ofMinutes(1);
    private static final int MIN_REFRESH_TOKEN_BYTES = 32;

    public AuthProperties {
        if (refreshExpiration == null || refreshExpiration.compareTo(MIN_REFRESH_EXPIRATION) < 0) {
            throw new IllegalStateException("auth.refresh-expiration 必须是不小于 1m 的时长，例如 7d");
        }
        if (refreshTokenBytes < MIN_REFRESH_TOKEN_BYTES) {
            throw new IllegalStateException("auth.refresh-token-bytes 不得小于 " + MIN_REFRESH_TOKEN_BYTES);
        }
        if (refreshCookieName == null || refreshCookieName.isBlank()) {
            throw new IllegalStateException("auth.refresh-cookie-name 未配置");
        }
        if (refreshCookiePath == null || !refreshCookiePath.startsWith("/")) {
            throw new IllegalStateException("auth.refresh-cookie-path 必须以 / 开头");
        }
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalStateException("auth.allowed-origins 至少需要一项精确来源");
        }
        allowedOrigins = allowedOrigins.stream().map(AuthProperties::normalizeOrigin).toList();
        if (cookieSecure) {
            for (String origin : allowedOrigins) {
                if (origin.startsWith("http://")) {
                    throw new IllegalStateException("auth.cookie-secure 为 true 时来源必须是 https：" + origin);
                }
            }
        }
    }

    private static String normalizeOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            throw new IllegalStateException("auth.allowed-origins 不能包含空值");
        }
        String normalized = origin.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("*")) {
            throw new IllegalStateException("auth.allowed-origins 不允许使用通配符：" + origin);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalStateException("auth.allowed-origins 必须是带协议的来源：" + origin);
        }
        String afterScheme = normalized.substring(normalized.indexOf("://") + 3);
        if (afterScheme.isEmpty() || afterScheme.contains("/")) {
            throw new IllegalStateException("auth.allowed-origins 必须是精确来源，不能带路径或结尾斜杠：" + origin);
        }
        return normalized;
    }
}