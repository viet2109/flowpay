package com.flowpay.backend.payment.domain;

import com.flowpay.backend.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIntentTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T06:00:00Z");
    private static final Money AMOUNT = Money.of(500_000L, "VND");

    @Test
    void shouldCreatePaymentIntentInCreatedState() {
        PaymentIntent payment = PaymentIntent.create(
                "pi_01K2PAYMENT",
                41L,
                " ORDER-2026-001 ",
                " Payment for order ",
                AMOUNT,
                CREATED_AT
        );

        assertThat(payment.internalId()).isNull();
        assertThat(payment.publicId()).isEqualTo("pi_01K2PAYMENT");
        assertThat(payment.merchantId()).isEqualTo(41L);
        assertThat(payment.merchantOrderId()).isEqualTo("ORDER-2026-001");
        assertThat(payment.description()).isEqualTo("Payment for order");
        assertThat(payment.amount()).isEqualTo(AMOUNT);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.refundedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.version()).isZero();
        assertThat(payment.createdAt()).isEqualTo(CREATED_AT);
        assertThat(payment.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldNormalizeBlankOptionalTextToNull() {
        PaymentIntent payment = PaymentIntent.create(
                "pi_optional",
                41L,
                "  ",
                null,
                AMOUNT,
                CREATED_AT
        );

        assertThat(payment.merchantOrderId()).isNull();
        assertThat(payment.description()).isNull();
    }

    @Test
    void shouldTransitionFromCreatedToProcessing() {
        PaymentIntent payment = newPayment("pi_processing");
        Instant changedAt = CREATED_AT.plusSeconds(1);

        payment.startProcessing(changedAt);

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.updatedAt()).isEqualTo(changedAt);
    }

    @Test
    void shouldTransitionFromProcessingToSucceeded() {
        PaymentIntent payment = processingPayment("pi_succeeded");
        Instant changedAt = CREATED_AT.plusSeconds(2);

        payment.markSucceeded(changedAt);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.refundableAmount()).isEqualTo(AMOUNT);
        assertThat(payment.updatedAt()).isEqualTo(changedAt);
    }

    @Test
    void shouldTransitionFromProcessingToFailed() {
        PaymentIntent payment = processingPayment("pi_failed");
        Instant changedAt = CREATED_AT.plusSeconds(2);

        payment.markFailed(changedAt);

        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.updatedAt()).isEqualTo(changedAt);
    }

    @Test
    void shouldOnlyStartProcessingFromCreated() {
        PaymentIntent processing = processingPayment("pi_already_processing");
        PaymentIntent succeeded = succeededPayment("pi_already_succeeded");
        PaymentIntent failed = failedPayment("pi_already_failed");

        assertInvalidTransition(
                () -> processing.startProcessing(CREATED_AT.plusSeconds(3)),
                PaymentStatus.PROCESSING,
                PaymentStatus.PROCESSING
        );
        assertInvalidTransition(
                () -> succeeded.startProcessing(CREATED_AT.plusSeconds(3)),
                PaymentStatus.SUCCEEDED,
                PaymentStatus.PROCESSING
        );
        assertInvalidTransition(
                () -> failed.startProcessing(CREATED_AT.plusSeconds(3)),
                PaymentStatus.FAILED,
                PaymentStatus.PROCESSING
        );
    }

    @Test
    void shouldOnlyMarkSucceededFromProcessing() {
        PaymentIntent created = newPayment("pi_created_to_success");
        PaymentIntent succeeded = succeededPayment("pi_success_to_success");
        PaymentIntent failed = failedPayment("pi_failed_to_success");

        assertInvalidTransition(
                () -> created.markSucceeded(CREATED_AT.plusSeconds(3)),
                PaymentStatus.CREATED,
                PaymentStatus.SUCCEEDED
        );
        assertInvalidTransition(
                () -> succeeded.markSucceeded(CREATED_AT.plusSeconds(3)),
                PaymentStatus.SUCCEEDED,
                PaymentStatus.SUCCEEDED
        );
        assertInvalidTransition(
                () -> failed.markSucceeded(CREATED_AT.plusSeconds(3)),
                PaymentStatus.FAILED,
                PaymentStatus.SUCCEEDED
        );
    }

    @Test
    void shouldOnlyMarkFailedFromProcessing() {
        PaymentIntent created = newPayment("pi_created_to_failed");
        PaymentIntent succeeded = succeededPayment("pi_success_to_failed");
        PaymentIntent failed = failedPayment("pi_failed_to_failed");

        assertInvalidTransition(
                () -> created.markFailed(CREATED_AT.plusSeconds(3)),
                PaymentStatus.CREATED,
                PaymentStatus.FAILED
        );
        assertInvalidTransition(
                () -> succeeded.markFailed(CREATED_AT.plusSeconds(3)),
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED
        );
        assertInvalidTransition(
                () -> failed.markFailed(CREATED_AT.plusSeconds(3)),
                PaymentStatus.FAILED,
                PaymentStatus.FAILED
        );
    }

    @Test
    void shouldRejectNonPositivePaymentAmount() {
        assertThatThrownBy(() -> PaymentIntent.create(
                "pi_zero",
                41L,
                null,
                null,
                Money.of(0L, "VND"),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
        assertThatThrownBy(() -> PaymentIntent.create(
                "pi_negative",
                41L,
                null,
                null,
                Money.of(-1L, "VND"),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
    }

    @Test
    void shouldProtectRefundInvariantWhenRehydrating() {
        assertThatThrownBy(() -> rehydrate(
                Money.of(-1L, "VND"),
                Money.of(0L, "VND")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refund amounts must not be negative");
        assertThatThrownBy(() -> rehydrate(
                Money.of(400_000L, "VND"),
                Money.of(100_001L, "VND")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refund amounts exceed the payment amount");
        assertThatThrownBy(() -> rehydrate(
                Money.of(1L, "USD"),
                Money.of(0L, "VND")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refund amounts must use the payment currency");
    }

    @Test
    void shouldRehydrateValidPersistenceState() {
        PaymentIntent payment = PaymentIntent.rehydrate(
                91L,
                "pi_rehydrated",
                41L,
                "ORDER-REHYDRATED",
                "Stored payment",
                AMOUNT,
                PaymentStatus.PARTIALLY_REFUNDED,
                Money.of(100_000L, "VND"),
                Money.of(50_000L, "VND"),
                7L,
                CREATED_AT,
                CREATED_AT.plusSeconds(30)
        );

        assertThat(payment.internalId()).isEqualTo(91L);
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.refundedAmount()).isEqualTo(Money.of(100_000L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(50_000L, "VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of(350_000L, "VND"));
        assertThat(payment.version()).isEqualTo(7L);
    }

    @Test
    void shouldReserveCompleteAndReleaseRefundCapacity() {
        PaymentIntent payment = succeededPayment("pi_refund_capacity");

        payment.reserveRefund(Money.of(300_000L, "VND"), CREATED_AT.plusSeconds(3));

        assertThat(payment.refundedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(300_000L, "VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of(200_000L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        payment.completeRefund(Money.of(200_000L, "VND"), CREATED_AT.plusSeconds(4));

        assertThat(payment.refundedAmount()).isEqualTo(Money.of(200_000L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(100_000L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);

        payment.releaseRefund(Money.of(100_000L, "VND"), CREATED_AT.plusSeconds(5));

        assertThat(payment.refundedAmount()).isEqualTo(Money.of(200_000L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void shouldMarkPaymentRefundedWhenCompletionReachesOriginalAmount() {
        PaymentIntent payment = succeededPayment("pi_fully_refunded");
        payment.reserveRefund(AMOUNT, CREATED_AT.plusSeconds(3));

        payment.completeRefund(AMOUNT, CREATED_AT.plusSeconds(4));

        assertThat(payment.refundedAmount()).isEqualTo(AMOUNT);
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void shouldRejectRefundCapacityChangesOutsideRefundableStates() {
        PaymentIntent created = newPayment("pi_created_refund");
        PaymentIntent processing = processingPayment("pi_processing_refund");
        PaymentIntent failed = failedPayment("pi_failed_refund");
        PaymentIntent refunded = succeededPayment("pi_refunded_refund");
        refunded.reserveRefund(AMOUNT, CREATED_AT.plusSeconds(3));
        refunded.completeRefund(AMOUNT, CREATED_AT.plusSeconds(4));

        assertThatThrownBy(() -> created.reserveRefund(
                Money.of(1L, "VND"),
                CREATED_AT.plusSeconds(5)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> processing.reserveRefund(
                Money.of(1L, "VND"),
                CREATED_AT.plusSeconds(5)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.reserveRefund(
                Money.of(1L, "VND"),
                CREATED_AT.plusSeconds(5)
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> refunded.reserveRefund(
                Money.of(1L, "VND"),
                CREATED_AT.plusSeconds(5)
        )).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectInvalidRefundAmountsWithoutMutation() {
        PaymentIntent payment = succeededPayment("pi_invalid_refund_amount");

        assertThatThrownBy(() -> payment.reserveRefund(
                Money.of(0L, "VND"),
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refund amount must be positive");
        assertThatThrownBy(() -> payment.reserveRefund(
                Money.of(1L, "USD"),
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refund amount must use the payment currency");
        assertThatThrownBy(() -> payment.reserveRefund(
                Money.of(500_001L, "VND"),
                CREATED_AT.plusSeconds(3)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("refund amount exceeds available capacity");

        assertThat(payment.refundedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void shouldRejectCompletionOrReleaseAboveReservationWithoutMutation() {
        PaymentIntent payment = succeededPayment("pi_invalid_reserved_refund");
        payment.reserveRefund(Money.of(100_000L, "VND"), CREATED_AT.plusSeconds(3));

        assertThatThrownBy(() -> payment.completeRefund(
                Money.of(100_001L, "VND"),
                CREATED_AT.plusSeconds(4)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("refund amount exceeds reserved capacity");
        assertThatThrownBy(() -> payment.releaseRefund(
                Money.of(100_001L, "VND"),
                CREATED_AT.plusSeconds(4)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("refund amount exceeds reserved capacity");

        assertThat(payment.refundedAmount()).isEqualTo(Money.of(0L, "VND"));
        assertThat(payment.refundReservedAmount()).isEqualTo(Money.of(100_000L, "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void shouldRejectNonMonotonicTransitionTime() {
        PaymentIntent payment = processingPayment("pi_bad_time");

        assertThatThrownBy(() -> payment.markSucceeded(CREATED_AT.minusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("changedAt must not be before updatedAt");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    private static PaymentIntent newPayment(String publicId) {
        return PaymentIntent.create(
                publicId,
                41L,
                "ORDER-001",
                "Test payment",
                AMOUNT,
                CREATED_AT
        );
    }

    private static PaymentIntent processingPayment(String publicId) {
        PaymentIntent payment = newPayment(publicId);
        payment.startProcessing(CREATED_AT.plusSeconds(1));
        return payment;
    }

    private static PaymentIntent succeededPayment(String publicId) {
        PaymentIntent payment = processingPayment(publicId);
        payment.markSucceeded(CREATED_AT.plusSeconds(2));
        return payment;
    }

    private static PaymentIntent failedPayment(String publicId) {
        PaymentIntent payment = processingPayment(publicId);
        payment.markFailed(CREATED_AT.plusSeconds(2));
        return payment;
    }

    private static PaymentIntent rehydrate(Money refundedAmount, Money reservedAmount) {
        return PaymentIntent.rehydrate(
                91L,
                "pi_refund_invariant",
                41L,
                null,
                null,
                AMOUNT,
                PaymentStatus.SUCCEEDED,
                refundedAmount,
                reservedAmount,
                1L,
                CREATED_AT,
                CREATED_AT
        );
    }

    private static void assertInvalidTransition(
            Runnable transition,
            PaymentStatus source,
            PaymentStatus target
    ) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PaymentIntent cannot transition from " + source + " to " + target);
    }
}
