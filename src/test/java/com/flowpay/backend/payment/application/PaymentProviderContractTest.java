package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderContractTest {

    @Test
    void shouldExposeNormalizedProviderRequestValues() {
        PaymentProviderRequest request = new PaymentProviderRequest(
                " pi_contract ",
                Money.of(50_000L, "vnd")
        );

        assertThat(request.paymentPublicReference()).isEqualTo("pi_contract");
        assertThat(request.amountMinor()).isEqualTo(50_000L);
        assertThat(request.currency()).isEqualTo("VND");
    }

    @Test
    void shouldRejectInvalidProviderRequest() {
        assertThatThrownBy(() -> new PaymentProviderRequest(" ", Money.of(1L, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicReference must not be blank");
        assertThatThrownBy(() -> new PaymentProviderRequest(
                "pi_contract",
                Money.of(0L, "USD")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
    }

    @Test
    void shouldNormalizeProviderResultMetadata() {
        PaymentProviderResult result = new PaymentProviderResult(
                " SIMULATOR ",
                ProviderOutcome.DECLINED,
                " sim_pi_contract ",
                " CARD_DECLINED ",
                " The provider declined the payment. "
        );

        assertThat(result.provider()).isEqualTo("SIMULATOR");
        assertThat(result.providerTransactionId()).isEqualTo("sim_pi_contract");
        assertThat(result.failureCode()).isEqualTo("CARD_DECLINED");
        assertThat(result.failureMessage()).isEqualTo(
                "The provider declined the payment."
        );
    }

    @Test
    void shouldRejectInconsistentProviderResultMetadata() {
        assertThatThrownBy(() -> new PaymentProviderResult(
                "SIMULATOR",
                ProviderOutcome.SUCCESS,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerTransactionId");

        assertThatThrownBy(() -> new PaymentProviderResult(
                "SIMULATOR",
                ProviderOutcome.UNKNOWN,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized failure metadata");
    }
}
