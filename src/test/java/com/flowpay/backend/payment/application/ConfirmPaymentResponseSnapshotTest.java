package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmPaymentResponseSnapshotTest {

    @ParameterizedTest
    @MethodSource("validOutcomes")
    void shouldFreezePublicOutcomeAndHttpStatus(
            PaymentStatus paymentStatus,
            PaymentTransactionStatus transactionStatus,
            String providerTransactionId,
            String failureCode,
            String failureMessage,
            int httpStatus
    ) {
        ConfirmPaymentResponseSnapshot snapshot = new ConfirmPaymentResponseSnapshot(
                "pi_snapshot",
                paymentStatus,
                "ptxn_snapshot",
                transactionStatus,
                "SIMULATOR",
                providerTransactionId,
                failureCode,
                failureMessage
        );

        assertThat(snapshot.httpStatus()).isEqualTo(httpStatus);
        assertThat(snapshot.paymentId()).isEqualTo("pi_snapshot");
        assertThat(snapshot.transactionId()).isEqualTo("ptxn_snapshot");
        assertThat(Arrays.stream(snapshot.getClass().getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("internal")
                        || name.contains("raw")
                        || name.contains("payload"));
    }

    @Test
    void shouldRejectInvalidStatePairAndUnsafeIncompleteFailureMetadata() {
        assertThatThrownBy(() -> new ConfirmPaymentResponseSnapshot(
                "pi_snapshot",
                PaymentStatus.SUCCEEDED,
                "ptxn_snapshot",
                PaymentTransactionStatus.FAILED,
                "SIMULATOR",
                null,
                "DECLINED",
                "Declined."
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("confirm snapshot has an invalid state pair");

        assertThatThrownBy(() -> new ConfirmPaymentResponseSnapshot(
                "pi_snapshot",
                PaymentStatus.FAILED,
                "ptxn_snapshot",
                PaymentTransactionStatus.FAILED,
                "SIMULATOR",
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a non-success confirm snapshot must have safe failure metadata");
    }

    private static Stream<Arguments> validOutcomes() {
        return Stream.of(
                Arguments.of(
                        PaymentStatus.SUCCEEDED,
                        PaymentTransactionStatus.SUCCEEDED,
                        "sim_success",
                        null,
                        null,
                        200
                ),
                Arguments.of(
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED,
                        "sim_declined",
                        "CARD_DECLINED",
                        "The provider declined the payment.",
                        200
                ),
                Arguments.of(
                        PaymentStatus.PROCESSING,
                        PaymentTransactionStatus.UNKNOWN,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The provider outcome is unknown.",
                        202
                ),
                Arguments.of(
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The provider operation did not complete.",
                        200
                )
        );
    }
}
