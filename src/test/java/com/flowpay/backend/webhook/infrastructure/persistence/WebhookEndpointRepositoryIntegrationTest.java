package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.WebhookEndpointRepository;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WebhookEndpointRepositoryIntegrationTest extends PostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-13T03:00:00Z");
    @Autowired private WebhookEndpointRepository repository;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private long merchantId;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        merchantId = merchants.save(Merchant.create("mrc_persistence", "Store", NOW)).id();
    }

    @Test
    void roundTripsConfigurationAndCanonicalSubscriptions() {
        WebhookEndpoint saved = save(endpoint("wep_one"));
        WebhookEndpoint read = find("wep_one");
        assertThat(saved.internalId()).isPositive();
        assertThat(read.secretCiphertext()).isEqualTo("encrypted");
        assertThat(read.subscribedEventTypes()).containsExactlyInAnyOrder(
                WebhookEventType.PAYMENT_SUCCEEDED, WebhookEventType.REFUND_FAILED);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_endpoint_events", String.class))
                .containsExactlyInAnyOrder("payment.succeeded", "refund.failed");
        assertThat(repository.findByPublicIdAndMerchantId("wep_one", merchantId + 1)).isEmpty();
    }

    @Test
    void rejectsStaleSubscriptionAndDisableWritesWithoutLosingWinner() {
        save(endpoint("wep_conflict"));
        WebhookEndpoint winner = find("wep_conflict");
        WebhookEndpoint stale = find("wep_conflict");
        winner.replaceSubscriptions(List.of(WebhookEventType.PAYMENT_FAILED), NOW.plusSeconds(1));
        WebhookEndpoint saved = save(winner);
        assertThat(saved.version()).isEqualTo(1);
        stale.disable(NOW.plusSeconds(2));
        assertThatThrownBy(() -> save(stale)).isInstanceOf(OptimisticLockingFailureException.class);
        WebhookEndpoint read = find("wep_conflict");
        assertThat(read.status()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        assertThat(read.subscribedEventTypes()).containsExactly(WebhookEventType.PAYMENT_FAILED);
        assertThat(read.version()).isEqualTo(1);
    }

    @Test
    void rollsBackParentAndSubscriptionChangesTogether() {
        save(endpoint("wep_atomic"));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            WebhookEndpoint endpoint = find("wep_atomic");
            endpoint.changeUrl("https://changed.example", NOW.plusSeconds(1));
            endpoint.replaceSubscriptions(List.of(WebhookEventType.PAYMENT_FAILED), NOW.plusSeconds(1));
            repository.save(endpoint);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        WebhookEndpoint read = find("wep_atomic");
        assertThat(read.url()).isEqualTo("https://example.com");
        assertThat(read.version()).isZero();
        assertThat(read.subscribedEventTypes()).hasSize(2);
    }

    @Test
    void listsOnlyOwnedEndpointsIncludingDisabledInStableNewestFirstOrder() {
        WebhookEndpoint first = save(endpoint("wep_first"));
        save(endpoint("wep_second"));
        first.disable(NOW.plusSeconds(1));
        save(first);
        long otherId = merchants.save(Merchant.create("mrc_other", "Other", NOW)).id();
        save(WebhookEndpoint.create("wep_other", otherId, "https://example.com", "encrypted",
                List.of(WebhookEventType.PAYMENT_FAILED), NOW));
        assertThat(repository.findAllByMerchantId(merchantId)).extracting(WebhookEndpoint::publicId)
                .containsExactly("wep_second", "wep_first");
        assertThat(find("wep_first").status()).isEqualTo(WebhookEndpointStatus.DISABLED);
    }

    private WebhookEndpoint endpoint(String id) {
        return WebhookEndpoint.create(id, merchantId, "https://example.com", "encrypted",
                List.of(WebhookEventType.PAYMENT_SUCCEEDED, WebhookEventType.REFUND_FAILED), NOW);
    }

    private WebhookEndpoint save(WebhookEndpoint endpoint) {
        return new TransactionTemplate(transactions).execute(status -> repository.save(endpoint));
    }

    private WebhookEndpoint find(String id) {
        return repository.findByPublicIdAndMerchantId(id, merchantId).orElseThrow();
    }
}
