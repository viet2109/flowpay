package com.flowpay.backend.refund.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundTypesTest {

    @Test
    void shouldFreezeRefundStatusesAndProviderOutcomes() {
        assertThat(RefundStatus.values()).containsExactly(
                RefundStatus.CREATED,
                RefundStatus.PROCESSING,
                RefundStatus.SUCCEEDED,
                RefundStatus.FAILED
        );
        assertThat(RefundProviderOutcome.values()).containsExactly(
                RefundProviderOutcome.SUCCESS,
                RefundProviderOutcome.DECLINED,
                RefundProviderOutcome.UNKNOWN,
                RefundProviderOutcome.TECHNICAL_FAILURE
        );
    }

    @Test
    void shouldNormalizeOptionalRefundReasonOnce() {
        assertThat(RefundReason.of(" CUSTOMER_REQUEST ").value())
                .isEqualTo("CUSTOMER_REQUEST");
        assertThat(RefundReason.of("   ").value()).isNull();
        assertThat(RefundReason.of(null).value()).isNull();
        assertThat(RefundReason.of("r".repeat(RefundReason.MAX_LENGTH)).value())
                .hasSize(RefundReason.MAX_LENGTH);
    }

    @Test
    void shouldRejectOverLengthRefundReason() {
        assertThatThrownBy(() -> RefundReason.of("r".repeat(RefundReason.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not exceed 255 characters");
    }

    @Test
    void shouldRequireAndNormalizeSafeFailureFields() {
        RefundFailure failure = RefundFailure.of(
                " PROVIDER_TIMEOUT ",
                " Provider outcome is unknown. "
        );

        assertThat(failure.code()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(failure.message()).isEqualTo("Provider outcome is unknown.");
        assertThatThrownBy(() -> RefundFailure.of(" ", "message"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("code must not be blank");
        assertThatThrownBy(() -> RefundFailure.of("CODE", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("message must not be null");
    }
}
