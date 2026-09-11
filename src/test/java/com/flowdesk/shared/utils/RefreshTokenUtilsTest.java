package com.flowdesk.shared.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenUtilsTest {

    private final RefreshTokenUtils utils = new RefreshTokenUtils();

    @Test
    void generatesDifferentUrlSafeTokensWithAtLeast256Bits() {
        String first = utils.generate(32);
        String second = utils.generate(32);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        assertThat(second).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void createsStableDigestWithoutKeepingRawToken() {
        String digest = utils.digest("refresh-token-value");

        assertThat(digest).isEqualTo(utils.digest("refresh-token-value"));
        assertThat(digest).doesNotContain("refresh-token-value");
        assertThatThrownBy(() -> utils.generate(16))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
