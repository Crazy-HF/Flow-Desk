package com.flowdesk.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Refresh Token 的随机生成与摘要。无状态、无配置，故为静态工具类。 */
public final class RefreshTokenUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RefreshTokenUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /** 生成 URL 安全的高强度随机令牌。 */
    public static String generate(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 令牌摘要：Redis 只保存摘要，原文永不落库。 */
    public static String digest(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}