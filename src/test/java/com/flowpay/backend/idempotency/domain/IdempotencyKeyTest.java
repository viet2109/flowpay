package com.flowpay.backend.idempotency.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    @Test
    void shouldPreserveOpaqueCaseSensitiveValue() {
        IdempotencyKey upperCase = IdempotencyKey.of(" Checkout-Key ");
        IdempotencyKey lowerCase = IdempotencyKey.of(" checkout-key ");

        assertThat(upperCase.value()).isEqualTo(" Checkout-Key ");
        assertThat(lowerCase.value()).isEqualTo(" checkout-key ");
        assertThat(upperCase).isNotEqualTo(lowerCase);
    }

    @Test
    void shouldRejectMissingBlankAndOversizedValues() {
        assertThatNullPointerException()
                .isThrownBy(() -> IdempotencyKey.of(null))
                .withMessage("idempotencyKey must not be null");
        assertThatThrownBy(() -> IdempotencyKey.of(" \t\r\n "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey must not be blank");
        assertThatThrownBy(() -> IdempotencyKey.of("k".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("idempotencyKey must not exceed 255 characters");
    }

    @Test
    void shouldAcceptMaximumLengthWithoutExposingValueFromToString() {
        IdempotencyKey key = IdempotencyKey.of("s".repeat(IdempotencyKey.MAX_LENGTH));

        assertThat(key.value()).hasSize(255);
        assertThat(key.toString())
                .isEqualTo("IdempotencyKey[REDACTED]")
                .doesNotContain(key.value());
    }
}
