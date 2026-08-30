package com.flowpay.backend.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTransactionTest {

    private static final Instant STARTED_AT = Instant.parse("2026-08-30T07:00:00Z");
    private static final Instant COMPLETED_AT = STARTED_AT.plusSeconds(2);

    @Test
    void shouldCreateProcessingTransaction() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_processing");

        assertThat(transaction.internalId()).isNull();
        assertThat(transaction.publicId()).isEqualTo("ptxn_processing");
        assertThat(transaction.paymentIntentId()).isEqualTo(91L);
        assertThat(transaction.attemptNo()).isEqualTo(1);
        assertThat(transaction.provider()).isEqualTo("SIMULATOR");
        assertThat(transaction.providerTransactionId()).isNull();
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.PROCESSING);
        assertThat(transaction.failureCode()).isNull();
        assertThat(transaction.failureMessage()).isNull();
        assertThat(transaction.startedAt()).isEqualTo(STARTED_AT);
        assertThat(transaction.completedAt()).isNull();
        assertThat(transaction.version()).isZero();
    }

    @Test
    void shouldTransitionFromProcessingToSucceeded() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_succeeded");

        transaction.markSucceeded(" provider-success-1 ", COMPLETED_AT);

        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.SUCCEEDED);
        assertThat(transaction.providerTransactionId()).isEqualTo("provider-success-1");
        assertThat(transaction.failureCode()).isNull();
        assertThat(transaction.failureMessage()).isNull();
        assertThat(transaction.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void shouldTransitionFromProcessingToFailed() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_failed");

        transaction.markFailed(
                " provider-declined-1 ",
                " CARD_DECLINED ",
                " The provider declined the payment. ",
                COMPLETED_AT
        );

        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.FAILED);
        assertThat(transaction.providerTransactionId()).isEqualTo("provider-declined-1");
        assertThat(transaction.failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(transaction.failureMessage()).isEqualTo("The provider declined the payment.");
        assertThat(transaction.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void shouldTransitionFromProcessingToUnknown() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_unknown");

        transaction.markUnknown(
                null,
                " PROVIDER_TIMEOUT ",
                " Provider outcome is unknown. ",
                COMPLETED_AT
        );

        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.UNKNOWN);
        assertThat(transaction.providerTransactionId()).isNull();
        assertThat(transaction.failureCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(transaction.failureMessage()).isEqualTo("Provider outcome is unknown.");
        assertThat(transaction.completedAt()).isEqualTo(COMPLETED_AT);
    }

    @Test
    void shouldRejectSucceededToFailedTransition() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_success_to_failed");
        transaction.markSucceeded("provider-success-2", COMPLETED_AT);

        assertInvalidTransition(
                () -> transaction.markFailed(
                        null,
                        "TECHNICAL_FAILURE",
                        "Technical failure",
                        COMPLETED_AT.plusSeconds(1)
                ),
                PaymentTransactionStatus.SUCCEEDED,
                PaymentTransactionStatus.FAILED
        );
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.SUCCEEDED);
    }

    @Test
    void shouldRejectFailedToSucceededTransition() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_failed_to_success");
        transaction.markFailed(null, "DECLINED", "Declined", COMPLETED_AT);

        assertInvalidTransition(
                () -> transaction.markSucceeded("provider-success-3", COMPLETED_AT.plusSeconds(1)),
                PaymentTransactionStatus.FAILED,
                PaymentTransactionStatus.SUCCEEDED
        );
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.FAILED);
    }

    @Test
    void shouldRejectUnknownToSucceededTransitionInPhaseTwo() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_unknown_to_success");
        transaction.markUnknown(null, "TIMEOUT", "Unknown", COMPLETED_AT);

        assertInvalidTransition(
                () -> transaction.markSucceeded("provider-success-4", COMPLETED_AT.plusSeconds(1)),
                PaymentTransactionStatus.UNKNOWN,
                PaymentTransactionStatus.SUCCEEDED
        );
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.UNKNOWN);
    }

    @Test
    void shouldRejectInvalidAttemptNumber() {
        assertThatThrownBy(() -> PaymentTransaction.createProcessing(
                "ptxn_zero_attempt",
                91L,
                0,
                "SIMULATOR",
                STARTED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attemptNo must be positive");
        assertThatThrownBy(() -> PaymentTransaction.createProcessing(
                "ptxn_negative_attempt",
                91L,
                -1,
                "SIMULATOR",
                STARTED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("attemptNo must be positive");
    }

    @Test
    void shouldRejectCompletionBeforeStartWithoutMutatingState() {
        PaymentTransaction transaction = newProcessingTransaction("ptxn_bad_completion_time");

        assertThatThrownBy(() -> transaction.markFailed(
                null,
                "DECLINED",
                "Declined",
                STARTED_AT.minusNanos(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must not be before startedAt");

        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.PROCESSING);
        assertThat(transaction.failureCode()).isNull();
        assertThat(transaction.completedAt()).isNull();
    }

    @Test
    void shouldRehydrateTerminalTransaction() {
        PaymentTransaction transaction = PaymentTransaction.rehydrate(
                77L,
                "ptxn_rehydrated",
                91L,
                1,
                "SIMULATOR",
                "provider-declined-2",
                PaymentTransactionStatus.FAILED,
                "DECLINED",
                "The payment was declined.",
                STARTED_AT,
                COMPLETED_AT,
                4L
        );

        assertThat(transaction.internalId()).isEqualTo(77L);
        assertThat(transaction.paymentIntentId()).isEqualTo(91L);
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.FAILED);
        assertThat(transaction.failureCode()).isEqualTo("DECLINED");
        assertThat(transaction.completedAt()).isEqualTo(COMPLETED_AT);
        assertThat(transaction.version()).isEqualTo(4L);
    }

    @Test
    void shouldRejectInconsistentRehydratedLifecycleState() {
        assertThatThrownBy(() -> PaymentTransaction.rehydrate(
                77L,
                "ptxn_incomplete_terminal",
                91L,
                1,
                "SIMULATOR",
                null,
                PaymentTransactionStatus.SUCCEEDED,
                null,
                null,
                STARTED_AT,
                null,
                1L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a terminal transaction must have startedAt and completedAt");

        assertThatThrownBy(() -> PaymentTransaction.rehydrate(
                77L,
                "ptxn_processing_failure",
                91L,
                1,
                "SIMULATOR",
                null,
                PaymentTransactionStatus.PROCESSING,
                "FAILURE",
                "Not terminal",
                STARTED_AT,
                null,
                1L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PROCESSING transaction must not have failure metadata");
    }

    private static PaymentTransaction newProcessingTransaction(String publicId) {
        return PaymentTransaction.createProcessing(
                publicId,
                91L,
                1,
                " SIMULATOR ",
                STARTED_AT
        );
    }

    private static void assertInvalidTransition(
            Runnable transition,
            PaymentTransactionStatus source,
            PaymentTransactionStatus target
    ) {
        assertThatThrownBy(transition::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PaymentTransaction cannot transition from " + source + " to " + target);
    }
}
