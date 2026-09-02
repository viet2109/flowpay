package com.flowpay.backend.payment.application.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSucceededEventV1Test {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T11:00:00Z");

    @Test
    void shouldExposeStableNormalizedPaymentSuccessContract() throws NoSuchMethodException {
        PaymentSucceededEventV1 event = new PaymentSucceededEventV1(
                15L,
                " pi_contract ",
                1_000_000L,
                " vnd ",
                OCCURRED_AT
        );

        assertThat(event.merchantInternalId()).isEqualTo(15L);
        assertThat(event.paymentPublicId()).isEqualTo("pi_contract");
        assertThat(event.amountMinor()).isEqualTo(1_000_000L);
        assertThat(event.currency()).isEqualTo("VND");
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.eventType()).isEqualTo("payment.succeeded.v1");
        assertThat(event.aggregateType()).isEqualTo("PAYMENT_INTENT");
        assertThat(event.aggregateId()).isEqualTo("pi_contract");
        assertThat(PaymentIntegrationEventPublisher.class.getDeclaredMethod(
                "publish",
                PaymentSucceededEventV1.class
        ).getReturnType()).isEqualTo(void.class);
    }

    @Test
    void shouldKeepExactScalarPayloadShape() {
        assertThat(Arrays.stream(PaymentSucceededEventV1.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly(
                        "merchantInternalId",
                        "paymentPublicId",
                        "amountMinor",
                        "currency",
                        "occurredAt"
                );
        assertThat(Arrays.stream(PaymentSucceededEventV1.class.getRecordComponents())
                .map(RecordComponent::getType))
                .containsExactly(
                        long.class,
                        String.class,
                        long.class,
                        String.class,
                        Instant.class
                );
    }

    @Test
    void shouldRejectInvalidMerchantPaymentAndAmountValues() {
        assertThatThrownBy(() -> event(0L, "pi_contract", 1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("merchantInternalId must be positive");
        assertThatThrownBy(() -> event(15L, " ", 1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicId must not be blank");
        assertThatThrownBy(() -> event(15L, "pi_contract", 0L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
        assertThatThrownBy(() -> event(15L, "pi_contract", -1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
    }

    @Test
    void shouldRejectInvalidCurrencyAndMissingOccurrenceTime() {
        assertThatThrownBy(() -> event(15L, "pi_contract", 1L, "US", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(15L, "pi_contract", 1L, "VND", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt must not be null");
    }

    private static PaymentSucceededEventV1 event(
            long merchantInternalId,
            String paymentPublicId,
            long amountMinor,
            String currency,
            Instant occurredAt
    ) {
        return new PaymentSucceededEventV1(
                merchantInternalId,
                paymentPublicId,
                amountMinor,
                currency,
                occurredAt
        );
    }
}
