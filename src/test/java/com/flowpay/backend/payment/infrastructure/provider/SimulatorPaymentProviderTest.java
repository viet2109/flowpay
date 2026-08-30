package com.flowpay.backend.payment.infrastructure.provider;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorPaymentProviderTest {

    private static final PaymentProviderRequest REQUEST = new PaymentProviderRequest(
            "pi_simulator",
            Money.of(50_000L, "VND")
    );

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void shouldProduceConfiguredOutcomeDeterministically(ProviderOutcome outcome) {
        SimulatorPaymentProvider provider = new SimulatorPaymentProvider(outcome);

        PaymentProviderResult first = provider.charge(REQUEST);
        PaymentProviderResult replay = provider.charge(REQUEST);

        assertThat(first.outcome()).isEqualTo(outcome);
        assertThat(first.provider()).isEqualTo(SimulatorPaymentProvider.PROVIDER);
        assertThat(replay).isEqualTo(first);
        assertMetadata(outcome, first);
    }

    @Test
    void shouldBindSimulatorSelectionAndDefaultOutcomeFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(PaymentProviderConfiguration.class)
                .withPropertyValues(
                        "flowpay.payment.provider=simulator",
                        "flowpay.payment.simulator.default-outcome=unknown"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(
                            com.flowpay.backend.payment.application.PaymentProviderPort.class
                    );
                    PaymentProviderResult result = context.getBean(
                            com.flowpay.backend.payment.application.PaymentProviderPort.class
                    ).charge(REQUEST);
                    assertThat(result.outcome()).isEqualTo(ProviderOutcome.UNKNOWN);
                });
    }

    private static void assertMetadata(
            ProviderOutcome outcome,
            PaymentProviderResult result
    ) {
        switch (outcome) {
            case SUCCESS -> {
                assertThat(result.providerTransactionId()).isEqualTo("sim_pi_simulator");
                assertThat(result.failureCode()).isNull();
                assertThat(result.failureMessage()).isNull();
            }
            case DECLINED -> {
                assertThat(result.providerTransactionId()).isEqualTo("sim_pi_simulator");
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorPaymentProvider.DECLINED_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorPaymentProvider.DECLINED_MESSAGE
                );
            }
            case UNKNOWN -> {
                assertThat(result.providerTransactionId()).isNull();
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorPaymentProvider.UNKNOWN_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorPaymentProvider.UNKNOWN_MESSAGE
                );
            }
            case TECHNICAL_FAILURE -> {
                assertThat(result.providerTransactionId()).isNull();
                assertThat(result.failureCode()).isEqualTo(
                        SimulatorPaymentProvider.TECHNICAL_FAILURE_CODE
                );
                assertThat(result.failureMessage()).isEqualTo(
                        SimulatorPaymentProvider.TECHNICAL_FAILURE_MESSAGE
                );
            }
        }
    }
}
