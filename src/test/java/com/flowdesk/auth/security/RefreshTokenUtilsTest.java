package com.flowdesk.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenUtilsTest {

    @Test
    void generatedTokenIsUrlSafeAndLongEnough() {
        String token = RefreshTokenUtils.generate(32);

        // 32 字节 Base64 URL 编码后去掉填充的长度，字符集不含 + / =
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void generatedTokensDiffer() {
        assertThat(RefreshTokenUtils.generate(32)).isNotEqualTo(RefreshTokenUtils.generate(32));
    }

    @Test
    void digestIsStableHexOfFixedLength() {
        String digest = RefreshTokenUtils.digest("raw-token");

        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(RefreshTokenUtils.digest("raw-token")).isEqualTo(digest);
        assertThat(RefreshTokenUtils.digest("other-token")).isNotEqualTo(digest);
    }
}
