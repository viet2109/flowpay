package com.flowpay.backend.common.observability;

import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyMetrics;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.payment.application.PaymentMetrics;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.RefundMetrics;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class BusinessMetricsCatalogTest {

    @Test
    void shouldExposeExactlyTheBoundedCatalogAndFixedLatencyBucketsWithoutSensitiveMaterial() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            MetricsRecorder recorder = new MetricsRecorder(registry);
            PaymentMetrics payment = new PaymentMetrics(recorder);
            RefundMetrics refund = new RefundMetrics(recorder);
            IdempotencyMetrics idempotency = new IdempotencyMetrics(recorder);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                for (int invocation = 0; invocation < 25; invocation++) {
                    String sensitive = "sensitive-provider-payload-" + invocation;
                    for (ProviderOutcome outcome : ProviderOutcome.values()) {
                        payment.outcomeCommitted(outcome);
                        payment.measureProvider(() -> paymentResult(outcome, sensitive));
                    }
                    for (RefundProviderOutcome outcome : RefundProviderOutcome.values()) {
                        refund.outcomeCommitted(outcome);
                        refund.measureProvider(() -> refundResult(outcome, sensitive));
                    }
                    for (IdempotencyOperation operation : IdempotencyOperation.values()) {
                        for (IdempotencyAcquisitionDecision decision : IdempotencyAcquisitionDecision.values()) {
                            idempotency.acquired(operation, decision);
                        }
                        idempotency.lookup(operation, null);
                    }
                }
                RuntimeException failure = new RuntimeException("sensitive-provider-exception");
                assertThatThrownBy(() -> payment.measureProvider(() -> { throw failure; })).isSameAs(failure);
                assertThatThrownBy(() -> refund.measureProvider(() -> { throw failure; })).isSameAs(failure);
                TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
            } finally {
                TransactionSynchronizationManager.clear();
            }
            assertThat(registry.getMeters()).hasSize(36);
            for (Meter meter : registry.getMeters()) {
                assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                    assertThat(tag.getKey()).isIn("outcome", "operation", "decision");
                    assertThat(tag.getValue()).isIn(Set.of("SUCCESS", "DECLINED", "TECHNICAL_FAILURE", "UNKNOWN",
                            "EXCEPTION", "PAYMENT_INTENT_CREATE", "PAYMENT_INTENT_CONFIRM", "REFUND_CREATE",
                            "NEW", "REPLAY", "IN_PROGRESS", "KEY_REUSED", "NOT_FOUND"));
                });
            }
            String scrape = registry.scrape();
            assertThat(scrape).contains("flowpay_payment_confirmation_outcomes_total",
                    "flowpay_refund_outcomes_total", "flowpay_idempotency_decisions_total",
                    "flowpay_idempotency_reservations_total", "flowpay_payment_provider_duration_seconds_count",
                    "flowpay_refund_provider_duration_seconds_sum", "le=\"0.01\"", "le=\"30.0\"", "le=\"+Inf\"")
                    .doesNotContain("sensitive-provider", "merchant", "currency", "amount", "quantile=");
        } finally {
            registry.close();
        }
    }

    @Test
    void shouldPreserveProviderResultsAndOriginalExceptionsWhenTelemetryCannotRecord(CapturedOutput output) {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().meterFilter(new MeterFilter() {
                @Override
                public Meter.Id map(Meter.Id id) {
                    throw new IllegalStateException("sensitive-telemetry-exception");
                }
            });
            PaymentMetrics payment = new PaymentMetrics(new MetricsRecorder(registry));
            RefundMetrics refund = new RefundMetrics(new MetricsRecorder(registry));
            for (ProviderOutcome outcome : ProviderOutcome.values()) {
                PaymentProviderResult result = paymentResult(outcome, "sensitive-provider-result");
                assertThat(payment.measureProvider(() -> result)).isSameAs(result);
            }
            for (RefundProviderOutcome outcome : RefundProviderOutcome.values()) {
                RefundProviderResult result = refundResult(outcome, "sensitive-provider-result");
                assertThat(refund.measureProvider(() -> result)).isSameAs(result);
            }
            RuntimeException failure = new RuntimeException("sensitive-original-exception");
            assertThatThrownBy(() -> payment.measureProvider(() -> { throw failure; })).isSameAs(failure);
            assertThatThrownBy(() -> refund.measureProvider(() -> { throw failure; })).isSameAs(failure);
            assertThat(output.getAll()).doesNotContain("sensitive-telemetry-exception",
                    "sensitive-provider-result", "sensitive-original-exception");
        } finally {
            registry.close();
        }
    }

    private static PaymentProviderResult paymentResult(ProviderOutcome outcome, String sensitive) {
        return new PaymentProviderResult(sensitive, outcome, sensitive,
                outcome == ProviderOutcome.SUCCESS ? null : sensitive,
                outcome == ProviderOutcome.SUCCESS ? null : sensitive);
    }

    private static RefundProviderResult refundResult(RefundProviderOutcome outcome, String sensitive) {
        return new RefundProviderResult(sensitive, outcome, sensitive,
                outcome == RefundProviderOutcome.SUCCESS ? null : sensitive,
                outcome == RefundProviderOutcome.SUCCESS ? null : sensitive);
    }
}
