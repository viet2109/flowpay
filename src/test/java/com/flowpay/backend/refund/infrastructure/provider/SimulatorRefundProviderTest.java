package com.flowpay.backend.refund.infrastructure.provider;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorRefundProviderTest {

    private static final RefundProviderRequest REQUEST = new RefundProviderRequest(
            "re_01KREFUND",
            "pi_01KPAYMENT",
            "provider_charge_01K",
            Money.of(50_000L, "VND"),
            RefundReason.of("Customer request")
    );

    @ParameterizedTest
    @EnumSource(RefundProviderOutcome.class)
    void shouldProduceConfiguredOutcomeDeterministically(RefundProviderOutcome outcome) {
        SimulatorRefundProvider provider = new SimulatorRefundProvider(outcome);

        RefundProviderResult first = provider.refund(REQUEST);
        RefundProviderResult replay = provider.refund(REQUEST);

        assertThat(first.outcome()).isEqualTo(outcome);
        assertThat(first.provider()).isEqualTo(SimulatorRefundProvider.PROVIDER);
        assertThat(replay).isEqualTo(first);
        assertMetadata(outcome, first);
    }

    @Test
    void shouldBindSimulatorSelectionAndDefaultOutcomeFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(RefundProviderConfiguration.class)
                .withPropertyValues(
                        "flowpay.refund.provider=simulator",
                        "flowpay.refund.simulator.default-outcome=unknown"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(RefundProviderPort.class);
                    RefundProviderResult result = context.getBean(
                            RefundProviderPort.class
                    ).refund(REQUEST);
                    assertThat(result.outcome()).isEqualTo(RefundProviderOutcome.UNKNOWN);
                });
    }

    private static void assertMetadata(
            RefundProviderOutcome outcome,
            RefundProviderResult result
    ) {
        switch (outcome) {
            case SUCCESS -> {
                assertThat(result.providerRefundId()).isEqualTo("sim_refund_01KREFUND");
                assertThat(result.failureCode()).isNull();
                assertThat(result.failureMessage()).isNull();
            }
            case DECLINED -> {
                assertThat(result.providerRefundId()).isNull();
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorRefundProvider.DECLINED_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorRefundProvider.DECLINED_MESSAGE
                );
            }
            case UNKNOWN -> {
                assertThat(result.providerRefundId()).isNull();
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorRefundProvider.UNKNOWN_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorRefundProvider.UNKNOWN_MESSAGE
                );
            }
            case TECHNICAL_FAILURE -> {
                assertThat(result.providerRefundId()).isNull();
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorRefundProvider.TECHNICAL_FAILURE_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorRefundProvider.TECHNICAL_FAILURE_MESSAGE
                );
            }
        }
    }
}
