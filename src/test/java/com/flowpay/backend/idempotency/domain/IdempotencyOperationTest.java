package com.flowpay.backend.idempotency.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyOperationTest {

    @Test
    void shouldExposeOnlyApprovedOperationsThroughRefundCreation() {
        assertThat(IdempotencyOperation.values()).containsExactly(
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyOperation.REFUND_CREATE
        );
    }
}
