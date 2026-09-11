package com.flowdesk.shared.utils;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** Refresh Token 的安全随机生成与单向摘要工具。 */
@Component
public class RefreshTokenUtils {

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate(int byteLength) {
        if (byteLength < 32) {
            throw new IllegalArgumentException("Refresh Token must contain at least 32 random bytes");
        }
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String digest(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Refresh Token must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
