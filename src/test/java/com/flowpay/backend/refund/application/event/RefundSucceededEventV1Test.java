package com.flowpay.backend.refund.application.event;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundSucceededEventV1Test {

    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T11:05:00Z");

    @Test
    void shouldExposeStableNormalizedRefundSuccessContract() throws NoSuchMethodException {
        RefundSucceededEventV1 event = new RefundSucceededEventV1(
                15L,
                " re_contract ",
                " pi_contract ",
                300_000L,
                " vnd ",
                OCCURRED_AT
        );

        assertThat(event.merchantInternalId()).isEqualTo(15L);
        assertThat(event.refundPublicId()).isEqualTo("re_contract");
        assertThat(event.paymentPublicId()).isEqualTo("pi_contract");
        assertThat(event.amountMinor()).isEqualTo(300_000L);
        assertThat(event.currency()).isEqualTo("VND");
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.eventType()).isEqualTo("refund.succeeded.v1");
        assertThat(event.aggregateType()).isEqualTo("REFUND");
        assertThat(event.aggregateId()).isEqualTo("re_contract");
        assertThat(RefundIntegrationEventPublisher.class.getDeclaredMethod(
                "publish",
                RefundSucceededEventV1.class
        ).getReturnType()).isEqualTo(void.class);
    }

    @Test
    void shouldKeepExactScalarPayloadShape() {
        assertThat(Arrays.stream(RefundSucceededEventV1.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly(
                        "merchantInternalId",
                        "refundPublicId",
                        "paymentPublicId",
                        "amountMinor",
                        "currency",
                        "occurredAt"
                );
        assertThat(Arrays.stream(RefundSucceededEventV1.class.getRecordComponents())
                .map(RecordComponent::getType))
                .containsExactly(
                        long.class,
                        String.class,
                        String.class,
                        long.class,
                        String.class,
                        Instant.class
                );
    }

    @Test
    void shouldRejectInvalidMerchantCorrelationAndAmountValues() {
        assertThatThrownBy(() -> event(0L, "re_contract", "pi_contract", 1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("merchantInternalId must be positive");
        assertThatThrownBy(() -> event(15L, " ", "pi_contract", 1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refundPublicId must not be blank");
        assertThatThrownBy(() -> event(15L, "re_contract", " ", 1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicId must not be blank");
        assertThatThrownBy(() -> event(15L, "re_contract", "pi_contract", 0L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
        assertThatThrownBy(() -> event(15L, "re_contract", "pi_contract", -1L, "VND", OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
    }

    @Test
    void shouldRejectInvalidCurrencyAndMissingOccurrenceTime() {
        assertThatThrownBy(() -> event(
                15L, "re_contract", "pi_contract", 1L, "US", OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(
                15L, "re_contract", "pi_contract", 1L, "VND", null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt must not be null");
    }

    private static RefundSucceededEventV1 event(
            long merchantInternalId,
            String refundPublicId,
            String paymentPublicId,
            long amountMinor,
            String currency,
            Instant occurredAt
    ) {
        return new RefundSucceededEventV1(
                merchantInternalId,
                refundPublicId,
                paymentPublicId,
                amountMinor,
                currency,
                occurredAt
        );
    }
}
