package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayBatchResult;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.RefundProviderPort;
import com.flowpay.backend.refund.application.RefundProviderRequest;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.application.event.RefundSucceededEventV1;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PhaseSixEndToEndIntegrationTest.ProviderScenarioConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PhaseSixEndToEndIntegrationTest extends PostgresIntegrationTest {

    private static final String PAYMENT_PATH = "/api/v1/payment-intents";
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final long PAYMENT_AMOUNT = 1_000_000L;

    @Container
    private static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeySecretCodec secretCodec;

    @Autowired
    private OutboxRelayService relayService;

    @Autowired
    private ScenarioPaymentProvider paymentProvider;

    @Autowired
    private ScenarioRefundProvider refundProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private FlowPayMessagingProperties messagingProperties;

    @Autowired
    private Clock clock;

    @DynamicPropertySource
    static void messagingProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("flowpay.messaging.ledger-consumer.enabled", () -> "true");
        registry.add(
                "flowpay.messaging.outbox.publisher-confirm-timeout",
                () -> "2s"
        );
    }

    @BeforeEach
    void cleanState() {
        paymentProvider.reset();
        refundProvider.reset();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, refunds, idempotency_records, payment_transactions,
                    payment_intents, refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
        purgeQueues();
    }

    @AfterEach
    void cleanQueues() {
        purgeQueues();
    }

    @Test
    void successfulPaymentAndRefundsShouldReachLedgerOnceThroughTheRealPipeline()
            throws Exception {
        StoredKey key = createStoredKey("success");
        String paymentId = createPayment(
                key,
                "payment-create-success",
                "ORDER-P6-E2E",
                PAYMENT_AMOUNT
        );

        JsonNode confirmation = confirmPayment(
                key,
                paymentId,
                "payment-confirm-success",
                200
        );

        assertThat(confirmation.path("paymentStatus").stringValue())
                .isEqualTo("SUCCEEDED");
        assertThat(confirmation.path("transactionStatus").stringValue())
                .isEqualTo("SUCCEEDED");
        assertThat(paymentState(paymentId)).isEqualTo("SUCCEEDED:0:0");
        assertThat(paymentTransactionStatus(paymentId)).isEqualTo("SUCCEEDED");
        assertThat(countOutbox(PaymentSucceededEventV1.EVENT_TYPE, paymentId)).isOne();
        assertThat(outboxStatus(paymentId)).isEqualTo("PENDING");
        assertOutboxPayloadIsSafe(
                paymentId,
                key.rawKey(),
                "payment-create-success",
                "payment-confirm-success"
        );
        assertThat(countRows("ledger_transactions")).isZero();

        JsonNode confirmationReplay = confirmPayment(
                key,
                paymentId,
                "payment-confirm-success",
                200
        );
        assertThat(confirmationReplay).isEqualTo(confirmation);
        assertThat(paymentProvider.invocationCount()).isOne();
        assertThat(countOutbox(PaymentSucceededEventV1.EVENT_TYPE, paymentId)).isOne();

        assertThat(relayService.relayDueEvents())
                .isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        awaitPostingCount(1L);
        assertThat(outboxStatus(paymentId)).isEqualTo("PUBLISHED");
        assertPaymentPosting(paymentId, key.merchantId(), PAYMENT_AMOUNT);

        JsonNode partialRefund = createRefund(
                key,
                paymentId,
                "refund-partial",
                300_000L,
                201
        );
        String partialRefundId = partialRefund.path("id").stringValue();
        assertThat(partialRefund.path("status").stringValue()).isEqualTo("SUCCEEDED");
        assertThat(paymentState(paymentId)).isEqualTo("PARTIALLY_REFUNDED:300000:0");
        assertThat(countOutbox(RefundSucceededEventV1.EVENT_TYPE, partialRefundId)).isOne();
        assertOutboxPayloadIsSafe(
                partialRefundId,
                key.rawKey(),
                "refund-partial"
        );
        assertThat(countRows("ledger_transactions")).isOne();

        JsonNode partialReplay = createRefund(
                key,
                paymentId,
                "refund-partial",
                300_000L,
                201
        );
        assertThat(partialReplay).isEqualTo(partialRefund);
        assertThat(refundProvider.invocationCount()).isOne();
        assertThat(countOutbox(RefundSucceededEventV1.EVENT_TYPE, partialRefundId)).isOne();

        assertThat(relayService.relayDueEvents())
                .isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        awaitPostingCount(2L);
        assertRefundPosting(partialRefundId, key.merchantId(), 300_000L);

        String secondRefundId = createRefund(
                key,
                paymentId,
                "refund-second",
                200_000L,
                201
        ).path("id").stringValue();
        String fullRefundId = createRefund(
                key,
                paymentId,
                "refund-full",
                500_000L,
                201
        ).path("id").stringValue();

        assertThat(paymentState(paymentId)).isEqualTo("REFUNDED:1000000:0");
        assertThat(countOutbox(RefundSucceededEventV1.EVENT_TYPE)).isEqualTo(3L);
        assertThat(countOutboxStatus("PENDING")).isEqualTo(2L);
        assertThat(relayService.relayDueEvents())
                .isEqualTo(new OutboxRelayBatchResult(2, 2, 0));

        awaitPostingCount(4L);
        assertThat(countOutboxStatus("PUBLISHED")).isEqualTo(4L);
        assertRefundPosting(secondRefundId, key.merchantId(), 200_000L);
        assertRefundPosting(fullRefundId, key.merchantId(), 500_000L);
        assertThat(countBalancedPostings()).isEqualTo(4L);
        assertThat(countEntries()).isEqualTo(8L);

        makeOutboxRetryable(partialRefundId);
        assertThat(relayService.relayDueEvents())
                .isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        await().atMost(Duration.ofSeconds(10))
                .during(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    assertThat(outboxStatus(partialRefundId)).isEqualTo("PUBLISHED");
                    assertThat(countRows("ledger_transactions")).isEqualTo(4L);
                    assertThat(countEntries()).isEqualTo(8L);
                    assertThat(countBalancedPostings()).isEqualTo(4L);
                    assertThat(deadLetterCount()).isZero();
                });
    }

    @ParameterizedTest(name = "payment provider {0} emits no success event")
    @EnumSource(value = ProviderOutcome.class, names = {"DECLINED", "UNKNOWN"})
    void unsuccessfulPaymentShouldNotProduceOutboxOrLedgerSideEffects(
            ProviderOutcome outcome
    ) throws Exception {
        paymentProvider.respondWith(outcome);
        StoredKey key = createStoredKey("payment_" + outcome.name().toLowerCase());
        String paymentId = createPayment(
                key,
                "create-" + outcome.name(),
                "ORDER-" + outcome.name(),
                450_000L
        );
        int expectedStatus = outcome == ProviderOutcome.UNKNOWN ? 202 : 200;

        JsonNode response = confirmPayment(
                key,
                paymentId,
                "confirm-" + outcome.name(),
                expectedStatus
        );

        String expectedPaymentStatus = outcome == ProviderOutcome.UNKNOWN
                ? "PROCESSING"
                : "FAILED";
        String expectedTransactionStatus = outcome == ProviderOutcome.UNKNOWN
                ? "UNKNOWN"
                : "FAILED";
        assertThat(response.path("paymentStatus").stringValue())
                .isEqualTo(expectedPaymentStatus);
        assertThat(response.path("transactionStatus").stringValue())
                .isEqualTo(expectedTransactionStatus);
        assertThat(paymentState(paymentId))
                .isEqualTo(expectedPaymentStatus + ":0:0");
        assertThat(paymentTransactionStatus(paymentId))
                .isEqualTo(expectedTransactionStatus);
        assertThat(paymentProvider.invocationCount()).isOne();
        assertThat(countRows("outbox_events")).isZero();
        assertThat(relayService.relayDueEvents()).isEqualTo(OutboxRelayBatchResult.empty());
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(deadLetterCount()).isZero();
    }

    @ParameterizedTest(name = "refund provider {0} emits no success event")
    @EnumSource(value = RefundProviderOutcome.class, names = {"DECLINED", "UNKNOWN"})
    void unsuccessfulRefundShouldNotProduceOutboxOrLedgerSideEffects(
            RefundProviderOutcome outcome
    ) throws Exception {
        StoredKey key = createStoredKey("refund_" + outcome.name().toLowerCase());
        String paymentId = createPayment(
                key,
                "payment-create-" + outcome.name(),
                "ORDER-REFUND-" + outcome.name(),
                600_000L
        );
        confirmPayment(
                key,
                paymentId,
                "payment-confirm-" + outcome.name(),
                200
        );
        assertThat(relayService.relayDueEvents())
                .isEqualTo(new OutboxRelayBatchResult(1, 1, 0));
        awaitPostingCount(1L);

        refundProvider.respondWith(outcome);
        int expectedStatus = outcome == RefundProviderOutcome.UNKNOWN ? 202 : 201;
        JsonNode response = createRefund(
                key,
                paymentId,
                "refund-" + outcome.name(),
                150_000L,
                expectedStatus
        );

        String expectedRefundStatus = outcome == RefundProviderOutcome.UNKNOWN
                ? "PROCESSING"
                : "FAILED";
        String expectedPaymentState = outcome == RefundProviderOutcome.UNKNOWN
                ? "SUCCEEDED:0:150000"
                : "SUCCEEDED:0:0";
        assertThat(response.path("status").stringValue()).isEqualTo(expectedRefundStatus);
        assertThat(paymentState(paymentId)).isEqualTo(expectedPaymentState);
        assertThat(refundStatus(response.path("id").stringValue()))
                .isEqualTo(expectedRefundStatus);
        assertThat(refundProvider.invocationCount()).isOne();
        assertThat(countOutbox(RefundSucceededEventV1.EVENT_TYPE)).isZero();
        assertThat(countRows("outbox_events")).isOne();
        assertThat(relayService.relayDueEvents()).isEqualTo(OutboxRelayBatchResult.empty());
        assertThat(countRows("ledger_transactions")).isOne();
        assertThat(countBalancedPostings()).isOne();
        assertThat(deadLetterCount()).isZero();
    }

    @Test
    void runningApplicationShouldUseTheCleanPhaseSixMigrationChain() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """, Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    private StoredKey createStoredKey(String suffix) {
        Instant now = clock.instant();
        Merchant merchant = merchantRepository.save(Merchant.create(
                "mrc_p6_e2e_" + suffix,
                "Phase Six E2E Store " + suffix,
                now
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        apiKeyRepository.save(ApiKey.create(
                "key_p6_e2e_" + suffix,
                merchant.id(),
                "Phase Six E2E key",
                secret.prefix(),
                secret.digest(),
                null,
                now
        ));
        return new StoredKey(merchant.id(), secret.rawKey());
    }

    private String createPayment(
            StoredKey key,
            String idempotencyKey,
            String orderId,
            long amount
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(PAYMENT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": %d,
                                  "currency": "VND",
                                  "orderId": "%s",
                                  "description": "Phase Six end-to-end payment"
                                }
                                """.formatted(amount, orderId)))
                .andExpect(status().isCreated())
                .andReturn();
        return responseData(result).path("id").stringValue();
    }

    private JsonNode confirmPayment(
            StoredKey key,
            String paymentId,
            String idempotencyKey,
            int expectedStatus
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(PAYMENT_PATH + "/" + paymentId + "/confirm")
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, idempotencyKey))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return responseData(result);
    }

    private JsonNode createRefund(
            StoredKey key,
            String paymentId,
            String idempotencyKey,
            long amount,
            int expectedStatus
    ) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        PAYMENT_PATH + "/" + paymentId + "/refunds"
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(key.rawKey()))
                        .header(IDEMPOTENCY_KEY, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": %d,
                                  "reason": "Phase Six verification"
                                }
                                """.formatted(amount)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return responseData(result);
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private void awaitPostingCount(long expectedCount) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(countRows("ledger_transactions")).isEqualTo(expectedCount);
            assertThat(countEntries()).isEqualTo(expectedCount * 2L);
        });
    }

    private void assertPaymentPosting(
            String paymentId,
            long merchantId,
            long amount
    ) {
        assertThat(postingEntries("PAYMENT_SUCCEEDED", paymentId)).containsExactly(
                "1:DEBIT:" + amount + ":SYSTEM_CLEARING:<null>:VND",
                "2:CREDIT:" + amount + ":MERCHANT_PAYABLE:" + merchantId + ":VND"
        );
    }

    private void assertRefundPosting(
            String refundId,
            long merchantId,
            long amount
    ) {
        assertThat(postingEntries("REFUND_SUCCEEDED", refundId)).containsExactly(
                "1:DEBIT:" + amount + ":MERCHANT_PAYABLE:" + merchantId + ":VND",
                "2:CREDIT:" + amount + ":SYSTEM_CLEARING:<null>:VND"
        );
    }

    private List<String> postingEntries(String postingType, String referenceId) {
        return jdbcTemplate.queryForList("""
                SELECT entry.entry_no || ':' || entry.direction || ':'
                    || entry.amount_minor || ':' || account.account_type || ':'
                    || COALESCE(account.owner_id::text, '<null>') || ':'
                    || TRIM(ledger_transaction.currency)
                FROM ledger_transactions ledger_transaction
                JOIN ledger_entries entry
                  ON entry.ledger_transaction_id = ledger_transaction.id
                JOIN ledger_accounts account
                  ON account.id = entry.ledger_account_id
                WHERE ledger_transaction.posting_type = ?
                  AND ledger_transaction.reference_id = ?
                ORDER BY entry.entry_no
                """, String.class, postingType, referenceId);
    }

    private String paymentState(String paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT status || ':' || refunded_amount_minor || ':' || refund_reserved_minor
                FROM payment_intents
                WHERE public_id = ?
                """, String.class, paymentId);
    }

    private String paymentTransactionStatus(String paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT payment_transaction.status
                FROM payment_transactions payment_transaction
                JOIN payment_intents payment
                  ON payment.id = payment_transaction.payment_intent_id
                WHERE payment.public_id = ?
                """, String.class, paymentId);
    }

    private String refundStatus(String refundId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM refunds WHERE public_id = ?",
                String.class,
                refundId
        );
    }

    private long countOutbox(String eventType, String aggregateId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE event_type = ?
                  AND aggregate_id = ?
                """, Long.class, eventType, aggregateId);
    }

    private long countOutbox(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE event_type = ?",
                Long.class,
                eventType
        );
    }

    private String outboxStatus(String aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE aggregate_id = ?",
                String.class,
                aggregateId
        );
    }

    private void assertOutboxPayloadIsSafe(
            String aggregateId,
            String... sensitiveValues
    ) throws Exception {
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE aggregate_id = ?",
                String.class,
                aggregateId
        );
        JsonNode json = objectMapper.readTree(payload);
        assertThat(json.propertyNames()).allMatch(field ->
                !field.toLowerCase(java.util.Locale.ROOT).matches(
                        ".*(authorization|idempotency|api.?key|password|credential|secret|"
                                + "hash|cipher|version|provider.?response|raw.?response).*"
                )
        );
        assertThat(payload).doesNotContainIgnoringCase(
                "Authorization",
                "Idempotency-Key",
                "requestHash",
                "keyHash",
                "password",
                "credential",
                "ciphertext",
                "version",
                "providerResponse",
                "rawResponse",
                "PaymentIntentEntity",
                "PaymentTransactionEntity",
                "RefundEntity"
        );
        assertThat(payload).doesNotContain(sensitiveValues);
    }

    private long countOutboxStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = ?",
                Long.class,
                status
        );
    }

    private void makeOutboxRetryable(String aggregateId) {
        // Simulate a relay crash after broker confirmation but before PUBLISHED commits.
        jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PENDING',
                    available_at = ?,
                    published_at = NULL,
                    last_error = NULL
                WHERE aggregate_id = ?
                """, OffsetDateTime.now(clock), aggregateId);
    }

    private long countBalancedPostings() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT ledger_transaction.id
                    FROM ledger_transactions ledger_transaction
                    JOIN ledger_entries entry
                      ON entry.ledger_transaction_id = ledger_transaction.id
                    GROUP BY ledger_transaction.id
                    HAVING COUNT(*) = 2
                       AND SUM(CASE entry.direction
                               WHEN 'DEBIT' THEN entry.amount_minor
                               ELSE -entry.amount_minor END) = 0
                ) balanced
                """, Long.class);
    }

    private long countEntries() {
        return countRows("ledger_entries");
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int deadLetterCount() {
        var properties = rabbitAdmin.getQueueProperties(
                messagingProperties.topology().ledgerDeadLetterQueue()
        );
        assertThat(properties).isNotNull();
        return ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).intValue();
    }

    private void purgeQueues() {
        rabbitAdmin.purgeQueue(messagingProperties.topology().ledgerQueue(), true);
        rabbitAdmin.purgeQueue(
                messagingProperties.topology().ledgerDeadLetterQueue(),
                true
        );
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private record StoredKey(long merchantId, String rawKey) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderScenarioConfiguration {

        @Bean
        @Primary
        ScenarioPaymentProvider scenarioPaymentProvider() {
            return new ScenarioPaymentProvider();
        }

        @Bean
        @Primary
        ScenarioRefundProvider scenarioRefundProvider() {
            return new ScenarioRefundProvider();
        }
    }

    static final class ScenarioPaymentProvider implements PaymentProviderPort {

        private final AtomicReference<ProviderOutcome> outcome =
                new AtomicReference<>(ProviderOutcome.SUCCESS);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public PaymentProviderResult charge(PaymentProviderRequest request) {
            invocations.incrementAndGet();
            return switch (outcome.get()) {
                case SUCCESS -> new PaymentProviderResult(
                        "SIMULATOR",
                        ProviderOutcome.SUCCESS,
                        "sim_" + request.paymentPublicReference(),
                        null,
                        null
                );
                case DECLINED -> new PaymentProviderResult(
                        "SIMULATOR",
                        ProviderOutcome.DECLINED,
                        "sim_" + request.paymentPublicReference(),
                        "CARD_DECLINED",
                        "The provider declined the payment."
                );
                case UNKNOWN -> new PaymentProviderResult(
                        "SIMULATOR",
                        ProviderOutcome.UNKNOWN,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The provider outcome is unknown."
                );
                case TECHNICAL_FAILURE -> new PaymentProviderResult(
                        "SIMULATOR",
                        ProviderOutcome.TECHNICAL_FAILURE,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The provider operation did not complete."
                );
            };
        }

        void respondWith(ProviderOutcome providerOutcome) {
            outcome.set(providerOutcome);
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            outcome.set(ProviderOutcome.SUCCESS);
            invocations.set(0);
        }
    }

    static final class ScenarioRefundProvider implements RefundProviderPort {

        private final AtomicReference<RefundProviderOutcome> outcome =
                new AtomicReference<>(RefundProviderOutcome.SUCCESS);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public RefundProviderResult refund(RefundProviderRequest request) {
            invocations.incrementAndGet();
            return switch (outcome.get()) {
                case SUCCESS -> new RefundProviderResult(
                        "SIMULATOR",
                        RefundProviderOutcome.SUCCESS,
                        "sim_" + request.refundPublicId(),
                        null,
                        null
                );
                case DECLINED -> new RefundProviderResult(
                        "SIMULATOR",
                        RefundProviderOutcome.DECLINED,
                        null,
                        "REFUND_DECLINED",
                        "The provider declined the refund."
                );
                case UNKNOWN -> new RefundProviderResult(
                        "SIMULATOR",
                        RefundProviderOutcome.UNKNOWN,
                        null,
                        "PROVIDER_TIMEOUT",
                        "The refund provider outcome is unknown."
                );
                case TECHNICAL_FAILURE -> new RefundProviderResult(
                        "SIMULATOR",
                        RefundProviderOutcome.TECHNICAL_FAILURE,
                        null,
                        "PROVIDER_UNAVAILABLE",
                        "The refund provider operation did not complete."
                );
            };
        }

        void respondWith(RefundProviderOutcome providerOutcome) {
            outcome.set(providerOutcome);
        }

        int invocationCount() {
            return invocations.get();
        }

        void reset() {
            outcome.set(RefundProviderOutcome.SUCCESS);
            invocations.set(0);
        }
    }
}
