package com.flowdesk.devtools;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;

class DeepSeekBatchEvaluationSafetyTest {
    @Test
    void paidRecordingRequiresTwoSwitchesEnabledAiKeyAndExplicitRemainingBudget() {
        assertThatCode(() -> DeepSeekBatchEvaluation.requireRecordAuthorization(
                "YES", null, "true", "test-key", "1.00"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DeepSeekBatchEvaluation.requireRecordAuthorization(
                "YES", "YES", "false", "test-key", "1.00"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DeepSeekBatchEvaluation.requireRecordAuthorization(
                "YES", "YES", "true", "test-key", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DeepSeekBatchEvaluation.requireRecordAuthorization(
                "YES", "YES", "true", "test-key", "5.01"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DeepSeekBatchEvaluation.requireRecordAuthorization(
                "YES", "YES", "true", "test-key", "0.20"))
                .doesNotThrowAnyException();
    }

    @Test
    void uncertainOrLowRemainingBudgetCannotStartPaidCall() {
        assertThatCode(() -> DeepSeekBatchEvaluation.canSpend(
                new BigDecimal("0.20"), new BigDecimal("0.15")))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DeepSeekBatchEvaluation.canSpend(
                new BigDecimal("0.20"), new BigDecimal("0.05")))
                .doesNotThrowAnyException();
    }
}
