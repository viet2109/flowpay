package com.flowpay.backend.common.observability;

import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetrics;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxMetricsSnapshot;
import com.flowpay.backend.infrastructure.messaging.outbox.metrics.OutboxOperationalMetrics;
import com.flowpay.backend.ledger.application.LedgerMetrics;
import com.flowpay.backend.ledger.application.LedgerPostingConflictException;
import com.flowpay.backend.ledger.application.LedgerPostingOutcome;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult;
import com.flowpay.backend.webhook.application.WebhookMetrics;
import com.flowpay.backend.webhook.application.WebhookMetricsSnapshot;
import com.flowpay.backend.webhook.infrastructure.metrics.WebhookOperationalMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalMetricsCatalogTest {
    @Test
    void shouldExposeBoundedCatalogAndSharedSnapshotsWithoutSensitiveTags() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var recorder = new MetricsRecorder(registry);
            var outbox = new OutboxMetrics(recorder);
            var ledger = new LedgerMetrics(recorder);
            var webhook = new WebhookMetrics(recorder);
            var properties = new MetricsSamplingProperties(false, Duration.ofSeconds(30));
            var outboxSnapshot = new OutboxOperationalMetrics(() -> new OutboxMetricsSnapshot(2, 1, 10), recorder, properties);
            var webhookSnapshot = new WebhookOperationalMetrics(() -> new WebhookMetricsSnapshot(2, 1, 1, 3, 10), recorder, properties);
            outboxSnapshot.refresh();
            webhookSnapshot.refresh();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                for (int repeat = 0; repeat < 25; repeat++) {
                    for (var outcome : IntegrationEventPublicationStatus.values()) outbox.attempt(outcome);
                    outbox.attempt(null);
                    outbox.published();
                    for (var type : LedgerPostingType.values()) {
                        for (var outcome : LedgerPostingOutcome.values()) {
                            ledger.observeClaim(type, () -> new LedgerPostingResult(outcome, "ltxn_sensitive_resource"));
                        }
                        var conflict = org.mockito.Mockito.mock(LedgerPostingConflictException.class);
                        assertThatThrownBy(() -> ledger.observeClaim(type, () -> { throw conflict; }))
                                .isInstanceOf(LedgerPostingConflictException.class);
                        var failure = new IllegalStateException("sensitive URL cause");
                        assertThatThrownBy(() -> ledger.observeClaim(type, () -> { throw failure; })).isSameAs(failure);
                    }
                    for (int status : new int[] { 101, 204, 302, 429, 503 }) webhook.httpResult(WebhookHttpDeliveryResult.http(status, 10));
                    for (var failure : WebhookHttpDeliveryResult.Failure.values()) {
                        if (failure != WebhookHttpDeliveryResult.Failure.HTTP_STATUS) webhook.httpResult(WebhookHttpDeliveryResult.failed(failure, 10));
                    }
                    webhook.claimed(); webhook.delivered(); webhook.recovered();
                }
                TransactionSynchronizationUtils.invokeAfterCommit(TransactionSynchronizationManager.getSynchronizations());
            } finally { TransactionSynchronizationManager.clear(); }
            assertThat(registry.getMeters()).hasSize(50); // 52 maximum; sampling FAILURE is unobserved here.
            var allowed = Set.of("PENDING", "FAILED", "RETRYING", "DELIVERING", "DEAD", "OUTBOX", "WEBHOOK", "SUCCESS",
                    "FAILURE", "CONFIRMED", "NEGATIVE_ACK", "UNROUTABLE", "CONFIRM_TIMEOUT", "TRANSPORT_FAILURE", "EXCEPTION",
                    "PAYMENT_SUCCEEDED", "REFUND_SUCCEEDED", "REVERSAL", "CREATED", "DUPLICATE", "CONFLICT",
                    "HTTP_1XX", "HTTP_2XX", "HTTP_3XX", "HTTP_4XX", "HTTP_5XX", "CONNECT_TIMEOUT", "REQUEST_TIMEOUT",
                    "TLS_FAILURE", "INTERRUPTED", "INVALID_URL");
            registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag -> {
                assertThat(tag.getKey()).isIn("component", "outcome", "status", "type");
                assertThat(tag.getValue()).isIn(allowed);
            }));
            assertThat(registry.scrape()).contains("flowpay_outbox_backlog_rows", "flowpay_webhook_oldest_due_age_seconds",
                    "flowpay_outbox_publication_attempts_total", "flowpay_ledger_postings_total", "flowpay_webhook_delivered_total")
                    .doesNotContain("sensitive", "merchant", "url=", "eventId", "httpStatus", "quantile=");
        } finally { registry.close(); }
    }

    @Test
    void shouldPreservePostingResultsAndExceptionsWhenRecordingFails() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            registry.config().meterFilter(new MeterFilter() {
                @Override public Meter.Id map(Meter.Id id) { throw new IllegalStateException("sensitive collector cause"); }
            });
            var metrics = new LedgerMetrics(new MetricsRecorder(registry));
            var result = new LedgerPostingResult(LedgerPostingOutcome.CREATED, "ltxn_test");
            assertThat(metrics.observeClaim(LedgerPostingType.PAYMENT_SUCCEEDED, () -> result)).isSameAs(result);
            var failure = new IllegalStateException("original posting error");
            assertThatThrownBy(() -> metrics.observeClaim(LedgerPostingType.PAYMENT_SUCCEEDED, () -> { throw failure; })).isSameAs(failure);
        } finally { registry.close(); }
    }
}
