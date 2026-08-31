package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundProviderContractTest {

    @Test
    void shouldNormalizeSafeProviderRequestFields() {
        RefundProviderRequest request = new RefundProviderRequest(
                " re_contract ",
                " pi_contract ",
                " provider_charge_contract ",
                Money.of(10_000L, "VND"),
                RefundReason.of(" Customer request ")
        );

        assertThat(request.refundPublicId()).isEqualTo("re_contract");
        assertThat(request.paymentPublicId()).isEqualTo("pi_contract");
        assertThat(request.providerTransactionId()).isEqualTo(
                "provider_charge_contract"
        );
        assertThat(request.amount()).isEqualTo(Money.of(10_000L, "VND"));
        assertThat(request.reason()).isEqualTo(RefundReason.of("Customer request"));
    }

    @Test
    void shouldRejectUnsafeOrInvalidProviderRequestFields() {
        assertThatThrownBy(() -> new RefundProviderRequest(
                " ",
                "pi_contract",
                "provider_charge_contract",
                Money.of(1L, "VND"),
                RefundReason.of(null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("refundPublicId must not be blank");
        assertThatThrownBy(() -> new RefundProviderRequest(
                "re_contract",
                "pi_contract",
                "provider_charge_contract",
                Money.of(0L, "VND"),
                RefundReason.of(null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
    }

    @Test
    void shouldNormalizeResultMetadataAndEnforceOutcomeContract() {
        RefundProviderResult failure = new RefundProviderResult(
                " SIMULATOR ",
                RefundProviderOutcome.DECLINED,
                " ",
                " REFUND_DECLINED ",
                " The provider declined the refund. "
        );

        assertThat(failure.provider()).isEqualTo("SIMULATOR");
        assertThat(failure.providerRefundId()).isNull();
        assertThat(failure.failureCode()).isEqualTo("REFUND_DECLINED");
        assertThat(failure.failureMessage()).isEqualTo(
                "The provider declined the refund."
        );
        assertThatThrownBy(() -> new RefundProviderResult(
                "SIMULATOR",
                RefundProviderOutcome.SUCCESS,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must have a providerRefundId");
        assertThatThrownBy(() -> new RefundProviderResult(
                "SIMULATOR",
                RefundProviderOutcome.UNKNOWN,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized failure metadata");
    }

    @Test
    void requestMustNotLeakPaymentOrPersistenceTypesOrSimulatorControls() {
        assertThat(Arrays.stream(RefundProviderRequest.class.getRecordComponents())
                .map(RecordComponent::getType)
                .map(Class::getName))
                .noneMatch(name -> name.startsWith("com.flowpay.backend.payment."))
                .noneMatch(name -> name.contains(".infrastructure.persistence."));
        assertThat(Arrays.stream(RefundProviderRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("outcome", "simulatorOutcome");
    }
}
