package com.flowpay.backend.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTypesTest {

    @Test
    void shouldFreezePaymentAndProviderStatuses() {
        assertThat(PaymentStatus.values()).containsExactly(
                PaymentStatus.CREATED,
                PaymentStatus.PROCESSING,
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED,
                PaymentStatus.PARTIALLY_REFUNDED,
                PaymentStatus.REFUNDED
        );
        assertThat(PaymentTransactionStatus.values()).containsExactly(
                PaymentTransactionStatus.PENDING,
                PaymentTransactionStatus.PROCESSING,
                PaymentTransactionStatus.SUCCEEDED,
                PaymentTransactionStatus.FAILED,
                PaymentTransactionStatus.UNKNOWN
        );
        assertThat(ProviderOutcome.values()).containsExactly(
                ProviderOutcome.SUCCESS,
                ProviderOutcome.DECLINED,
                ProviderOutcome.UNKNOWN,
                ProviderOutcome.TECHNICAL_FAILURE
        );
    }
}
