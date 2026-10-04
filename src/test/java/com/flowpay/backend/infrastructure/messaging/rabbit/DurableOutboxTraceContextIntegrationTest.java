package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.FlowPayApplication;
import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.IntegrationEventPublicationStatus;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelopeMapper;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.refund.application.event.RefundIntegrationEventPublisher;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DurableOutboxTraceContextIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    private static final Instant OCCURRED = Instant.parse("2026-10-04T10:00:00Z");
    private static final String PARENT = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-01";
    private static final String UNSAMPLED = "00-" + "c".repeat(32) + "-" + "d".repeat(16) + "-00";

    @Test
    void shouldPublishDurableSourceParentsAfterActualContextRestartAndRetryWithoutAmbientLeak() throws Exception {
        Map<String, byte[]> expectedBodies = new HashMap<>();
        Map<String, String> expectedParents = new HashMap<>();
        // Real source application is closed (including its pool/SDK); only PostgreSQL retains metadata.
        try (ConfigurableApplicationContext source = start()) {
            var payment = source.getBean(PaymentIntegrationEventPublisher.class);
            var refund = source.getBean(RefundIntegrationEventPublisher.class);
            var tx = new TransactionTemplate(source.getBean(PlatformTransactionManager.class));
            try (Scope baggage = Baggage.builder().put("private", "synthetic-sensitive-baggage")
                    .build().makeCurrent(); Scope parent = span(PARENT).makeCurrent()) {
                tx.executeWithoutResult(status -> payment.publish(new PaymentSucceededEventV1(
                        15L, "pi_restart", 1000L, "VND", OCCURRED)));
            }
            try (Scope parent = span(UNSAMPLED).makeCurrent()) {
                tx.executeWithoutResult(status -> refund.publish(new RefundSucceededEventV1(
                        15L, "re_restart", "pi_restart", 500L, "VND", OCCURRED)));
            }
            tx.executeWithoutResult(status -> payment.publish(new PaymentSucceededEventV1(
                    15L, "pi_legacy", 1000L, "VND", OCCURRED)));
            tx.executeWithoutResult(status -> payment.publish(new PaymentSucceededEventV1(
                    15L, "pi_corrupt", 1000L, "VND", OCCURRED)));
            source.getBean(JdbcTemplate.class).update(
                    "UPDATE outbox_events SET traceparent = 'bad-context' WHERE aggregate_id = 'pi_corrupt'");
            var mapper = source.getBean(IntegrationEventEnvelopeMapper.class);
            var json = source.getBean(ObjectMapper.class);
            for (var event : source.getBean(OutboxRepository.class).findDueUnpublished(Instant.now(), 10)) {
                expectedBodies.put(event.eventId(), json.writeValueAsBytes(mapper.from(event)));
                expectedParents.put(event.eventId(), event.traceparent());
            }
            assertThat(expectedBodies).hasSize(4);
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();

        // A different application/SDK loads old rows; unrelated scheduler context must never be propagated.
        try (ConfigurableApplicationContext relay = start()) {
            var repository = relay.getBean(OutboxRepository.class);
            var publisher = relay.getBean(ConfirmedRabbitIntegrationEventPublisher.class);
            var envelopes = relay.getBean(IntegrationEventEnvelopeMapper.class);
            var rabbit = relay.getBean(RabbitTemplate.class);
            var properties = relay.getBean(FlowPayMessagingProperties.class);
            var parser = relay.getBean(IntegrationEventEnvelopeParser.class);
            var rows = repository.findDueUnpublished(Instant.now(), 10);
            var retried = rows.stream().filter(event -> PARENT.equals(event.traceparent())).findFirst().orElseThrow();
            var first = publisher.publish(envelopes.from(retried), retried.traceparent());
            assertThat(first.status()).isEqualTo(IntegrationEventPublicationStatus.CONFIRMED);
            Message initial = receive(rabbit, properties);
            assertTransport(initial, expectedBodies, expectedParents);
            // Simulate delivered message + lost confirm/crash before marking PUBLISHED.
            repository.recordFailure(retried.eventId(), Instant.now().minusSeconds(1),
                    "RabbitMQ publication failed: CONFIRM_TIMEOUT");
            try (Scope unrelated = span("00-" + "e".repeat(32) + "-" + "f".repeat(16) + "-01").makeCurrent()) {
                var before = Span.current().getSpanContext();
                var result = relay.getBean(OutboxRelayService.class).relayDueEvents();
                assertThat(result.publishedCount()).isEqualTo(4);
                assertThat(result.failedCount()).isZero();
                assertThat(Span.current().getSpanContext()).isEqualTo(before);
            }
            for (int i = 0; i < 4; i++) {
                Message message = receive(rabbit, properties);
                assertTransport(message, expectedBodies, expectedParents);
                var envelope = parser.parse(message);
                assertThat(envelope.eventId()).isEqualTo(message.getMessageProperties().getMessageId());
            }
            assertThat(relay.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT traceparent FROM outbox_events WHERE event_id = ?", String.class,
                    retried.eventId())).isEqualTo(PARENT);
            assertThat(relay.getBean(JdbcTemplate.class).queryForObject(
                    "SELECT retry_count FROM outbox_events WHERE event_id = ?", Integer.class,
                    retried.eventId())).isEqualTo(1);
            assertThat(repository.findDueUnpublished(Instant.now(), 10)).isEmpty();
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assertThat(Baggage.current().isEmpty()).isTrue();
    }

    private static void assertTransport(Message message, Map<String, byte[]> bodies,
                                        Map<String, String> parents) {
        String id = message.getMessageProperties().getMessageId();
        assertThat(message.getBody()).isEqualTo(bodies.get(id));
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        if (parents.get(id) == null) assertThat(headers).doesNotContainKey("traceparent");
        else assertThat(headers).containsEntry("traceparent", parents.get(id));
        assertThat(headers).doesNotContainKeys("tracestate", "baggage", "X-Request-Id");
    }

    private static Message receive(RabbitTemplate rabbit, FlowPayMessagingProperties properties) {
        Message message = rabbit.receive(properties.topology().ledgerQueue(), 5000);
        assertThat(message).isNotNull();
        return message;
    }

    private static Span span(String parent) {
        return Span.wrap(SpanContext.create(parent.substring(3, 35), parent.substring(36, 52),
                parent.endsWith("01") ? TraceFlags.getSampled() : TraceFlags.getDefault(),
                TraceState.builder().put("private", "synthetic-sensitive-state").build()));
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(FlowPayApplication.class).profiles("test")
                .initializers(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("owned-trace-restart-fixture", Map.ofEntries(
                                Map.entry("server.port", "0"),
                                Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
                                Map.entry("spring.datasource.username", POSTGRES.getUsername()),
                                Map.entry("spring.datasource.password", POSTGRES.getPassword()),
                                Map.entry("spring.rabbitmq.host", RABBIT.getHost()),
                                Map.entry("spring.rabbitmq.port", RABBIT.getAmqpPort()),
                                Map.entry("spring.rabbitmq.username", RABBIT.getAdminUsername()),
                                Map.entry("spring.rabbitmq.password", RABBIT.getAdminPassword()),
                                Map.entry("management.otlp.metrics.export.enabled", false),
                                Map.entry("management.tracing.export.otlp.enabled", false)
                        )))).run();
    }
}
