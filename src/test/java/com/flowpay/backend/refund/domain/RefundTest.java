package com.flowpay.backend.refund.domain;

import com.flowpay.backend.common.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T08:00:00Z");
    private static final Instant PROCESSING_AT = CREATED_AT.plusSeconds(1);
    private static final Instant COMPLETED_AT = CREATED_AT.plusSeconds(2);
    private static final Money AMOUNT = Money.of(100_000L, "VND");
    private static final RefundFailure DECLINED = RefundFailure.of(
            "CARD_DECLINED",
            "The refund was declined."
    );

    @Test
    void shouldCreateRefundInCreatedState() {
        Refund refund = newRefund("re_created");

        assertThat(refund.internalId()).isNull();
        assertThat(refund.publicId()).isEqualTo("re_created");
        assertThat(refund.merchantId()).isEqualTo(41L);
        assertThat(refund.paymentIntentId()).isEqualTo(91L);
        assertThat(refund.amount()).isEqualTo(AMOUNT);
        assertThat(refund.status()).isEqualTo(RefundStatus.CREATED);
        assertThat(refund.reason()).isEqualTo(RefundReason.of("CUSTOMER_REQUEST"));
        assertThat(refund.provider()).isEqualTo("SIMULATOR");
        assertThat(refund.providerRefundId()).isNull();
        assertThat(refund.failure()).isNull();
        assertThat(refund.completedAt()).isNull();
        assertThat(refund.createdAt()).isEqualTo(CREATED_AT);
        assertThat(refund.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(refund.version()).isZero();
    }

    @Test
    void shouldRejectNonPositiveAmount() {
        assertThatThrownBy(() -> createWithAmount("re_zero", Money.of(0L, "VND")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
        assertThatThrownBy(() -> createWithAmount("re_negative", Money.of(-1L, "VND")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
        assertThatThrownBy(() -> createWithAmount("re_null", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("amount must not be null");
    }

    @Test
    void shouldTransitionFromCreatedToProcessing() {
        Refund refund = newRefund("re_processing");

        refund.startProcessing(PROCESSING_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.updatedAt()).isEqualTo(PROCESSING_AT);
        assertThat(refund.completedAt()).isNull();
    }

    @Test
    void shouldTransitionFromProcessingToSucceeded() {
        Refund refund = processingRefund("re_succeeded");

        refund.markSucceeded(" provider_re_123 ", COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.providerRefundId()).isEqualTo("provider_re_123");
        assertThat(refund.failure()).isNull();
        assertThat(refund.updatedAt()).isEqualTo(COMPLETED_AT);
        assertThat(refund.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void shouldTransitionFromProcessingToFailedWithSafeMetadata() {
        Refund refund = processingRefund("re_failed");

        refund.markFailed(" provider_re_declined ", DECLINED, COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.providerRefundId()).isEqualTo("provider_re_declined");
        assertThat(refund.failure()).isEqualTo(DECLINED);
        assertThat(refund.failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(refund.failureMessage()).isEqualTo("The refund was declined.");
        assertThat(refund.updatedAt()).isEqualTo(COMPLETED_AT);
        assertThat(refund.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void shouldRejectTerminalOutcomeFromCreated() {
        Refund success = newRefund("re_created_success");
        Refund failure = newRefund("re_created_failure");

        assertInvalidTransition(
                () -> success.markSucceeded("provider_re_1", COMPLETED_AT),
                RefundStatus.CREATED,
                RefundStatus.SUCCEEDED
        );
        assertInvalidTransition(
                () -> failure.markFailed(null, DECLINED, COMPLETED_AT),
                RefundStatus.CREATED,
                RefundStatus.FAILED
        );
    }

    @Test
    void shouldRejectTerminalToProcessingTransition() {
        Refund succeeded = succeededRefund("re_terminal_success");
        Refund failed = failedRefund("re_terminal_failure");

        assertInvalidTransition(
                () -> succeeded.startProcessing(COMPLETED_AT.plusSeconds(1)),
                RefundStatus.SUCCEEDED,
                RefundStatus.PROCESSING
        );
        assertInvalidTransition(
                () -> failed.startProcessing(COMPLETED_AT.plusSeconds(1)),
                RefundStatus.FAILED,
                RefundStatus.PROCESSING
        );
    }

    @Test
    void shouldRejectTransitionBetweenTerminalStates() {
        Refund succeeded = succeededRefund("re_success_to_failure");
        Refund failed = failedRefund("re_failure_to_success");

        assertInvalidTransition(
                () -> succeeded.markFailed(null, DECLINED, COMPLETED_AT.plusSeconds(1)),
                RefundStatus.SUCCEEDED,
                RefundStatus.FAILED
        );
        assertInvalidTransition(
                () -> failed.markSucceeded("provider_re_late", COMPLETED_AT.plusSeconds(1)),
                RefundStatus.FAILED,
                RefundStatus.SUCCEEDED
        );
    }

    @Test
    void shouldRecordUnknownProviderResultWithoutBecomingTerminal() {
        Refund refund = processingRefund("re_unknown");
        RefundFailure unknown = RefundFailure.of(
                " PROVIDER_TIMEOUT ",
                " Provider outcome is unknown. "
        );

        refund.recordUnknownProviderResult(" provider_re_pending ", unknown, COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.providerRefundId()).isEqualTo("provider_re_pending");
        assertThat(refund.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(refund.failureMessage()).isEqualTo("Provider outcome is unknown.");
        assertThat(refund.updatedAt()).isEqualTo(COMPLETED_AT);
        assertThat(refund.completedAt()).isNull();
    }

    @Test
    void shouldAllowUnknownProviderResultWithoutProviderReference() {
        Refund refund = processingRefund("re_unknown_without_reference");

        refund.recordUnknownProviderResult(null, DECLINED, COMPLETED_AT);

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.providerRefundId()).isNull();
        assertThat(refund.failure()).isEqualTo(DECLINED);
        assertThat(refund.completedAt()).isNull();
    }

    @Test
    void shouldRequireNormalizedMetadataForOutcomes() {
        Refund success = processingRefund("re_success_metadata");
        Refund failure = processingRefund("re_failure_metadata");
        Refund unknown = processingRefund("re_unknown_metadata");

        assertThatThrownBy(() -> success.markSucceeded("  ", COMPLETED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerRefundId must not be blank");
        assertThatThrownBy(() -> failure.markFailed(null, null, COMPLETED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failure must not be null");
        assertThatThrownBy(() -> unknown.recordUnknownProviderResult(null, null, COMPLETED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failure must not be null");

        assertThat(success.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(failure.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(unknown.status()).isEqualTo(RefundStatus.PROCESSING);
    }

    @Test
    void shouldRejectNonMonotonicLifecycleTimeWithoutMutatingState() {
        Refund refund = processingRefund("re_bad_time");

        assertThatThrownBy(() -> refund.markFailed(
                null,
                DECLINED,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must not be before updatedAt");

        assertThat(refund.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refund.failure()).isNull();
        assertThat(refund.completedAt()).isNull();
    }

    @Test
    void shouldRehydrateTerminalAndUnknownStates() {
        Refund succeeded = rehydrate(
                RefundStatus.SUCCEEDED,
                "provider_re_stored",
                null,
                COMPLETED_AT,
                COMPLETED_AT
        );
        Refund unknown = rehydrate(
                RefundStatus.PROCESSING,
                null,
                RefundFailure.of("TIMEOUT", "Outcome unknown"),
                COMPLETED_AT,
                null
        );

        assertThat(succeeded.internalId()).isEqualTo(71L);
        assertThat(succeeded.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(succeeded.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(succeeded.version()).isEqualTo(5L);
        assertThat(unknown.status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(unknown.failureCode()).isEqualTo("TIMEOUT");
        assertThat(unknown.completedAt()).isNull();
    }

    @Test
    void shouldRejectInconsistentRehydratedLifecycleState() {
        assertThatThrownBy(() -> rehydrate(
                RefundStatus.CREATED,
                "unexpected_reference",
                null,
                CREATED_AT,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a created Refund must not have provider result metadata");
        assertThatThrownBy(() -> rehydrate(
                RefundStatus.PROCESSING,
                null,
                null,
                COMPLETED_AT,
                COMPLETED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a non-terminal Refund must not have completedAt");
        assertThatThrownBy(() -> rehydrate(
                RefundStatus.SUCCEEDED,
                null,
                null,
                COMPLETED_AT,
                COMPLETED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a succeeded Refund must have a providerRefundId");
        assertThatThrownBy(() -> rehydrate(
                RefundStatus.FAILED,
                null,
                null,
                COMPLETED_AT,
                COMPLETED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a failed Refund must have failure metadata");
        assertThatThrownBy(() -> Refund.rehydrate(
                71L,
                "re_partial_failure",
                41L,
                91L,
                AMOUNT,
                RefundStatus.FAILED,
                RefundReason.of(null),
                "SIMULATOR",
                null,
                "DECLINED",
                null,
                CREATED_AT,
                COMPLETED_AT,
                COMPLETED_AT,
                1L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failureCode and failureMessage must both be present or both be absent");
    }

    private static Refund newRefund(String publicId) {
        return Refund.create(
                publicId,
                41L,
                91L,
                AMOUNT,
                RefundReason.of(" CUSTOMER_REQUEST "),
                " SIMULATOR ",
                CREATED_AT
        );
    }

    private static Refund createWithAmount(String publicId, Money amount) {
        return Refund.create(
                publicId,
                41L,
                91L,
                amount,
                RefundReason.of(null),
                "SIMULATOR",
                CREATED_AT
        );
    }

    private static Refund processingRefund(String publicId) {
        Refund refund = newRefund(publicId);
        refund.startProcessing(PROCESSING_AT);
        return refund;
    }

    private static Refund succeededRefund(String publicId) {
        Refund refund = processingRefund(publicId);
        refund.markSucceeded("provider_re_success", COMPLETED_AT);
        return refund;
    }

    private static Refund failedRefund(String publicId) {
        Refund refund = processingRefund(publicId);
        refund.markFailed(null, DECLINED, COMPLETED_AT);
        return refund;
    }

    private static Refund rehydrate(
            RefundStatus status,
            String providerRefundId,
            RefundFailure failure,
            Instant updatedAt,
            Instant completedAt
    ) {
        return Refund.rehydrate(
                71L,
                "re_rehydrated",
                41L,
                91L,
                AMOUNT,
                status,
                RefundReason.of("STORED_REASON"),
                "SIMULATOR",
                providerRefundId,
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(),
                CREATED_AT,
                updatedAt,
                completedAt,
                5L
        );
    }

    private static void assertInvalidTransition(
            Runnable transition,
            RefundStatus source,
            RefundStatus target
    ) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Refund cannot transition from " + source + " to " + target);
    }
}
