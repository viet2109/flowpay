package com.flowpay.backend.webhook.infrastructure.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class StagingPhaseSevenUpgradeIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");
    private static final byte[] KEY = new SecureRandom().generateSeed(32);
    private static final String SECRET = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(new SecureRandom().generateSeed(32));
    private static JdbcTemplate jdbc;
    private static String ciphertext;

    @BeforeAll
    static void seedLegacy() throws Exception {
        StagingPhaseSevenFixture.seed(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), KEY, SECRET);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        ciphertext = jdbc.queryForObject("SELECT secret_ciphertext FROM webhook_endpoints", String.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(11);
    }

    @Test
    void shouldPreserveLegacyFinanceAndEncryptedEndpointThroughAdditiveTraceMigrations() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT amount_minor=123000 AND refunded_amount_minor=0 AND refund_reserved_minor=0 AND status='SUCCEEDED' FROM payment_intents", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT public_id FROM payment_transactions", String.class)).startsWith("ptxn_");
        assertThat(jdbc.queryForObject("SELECT public_id FROM ledger_transactions", String.class)).startsWith("ltxn_");
        assertThat(jdbc.queryForObject("SELECT SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT secret_ciphertext FROM webhook_endpoints", String.class).equals(ciphertext)).isTrue();
        assertThat(new AesGcmWebhookSecretCipher(new SecretKeySpec(KEY, "AES")).decrypt(ciphertext).equals(SECRET)).isTrue();
        assertThat(jdbc.queryForObject("SELECT traceparent IS NULL FROM outbox_events", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT traceparent IS NULL FROM webhook_events", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries", String.class)).isEqualTo("PENDING");
    }

    @Test
    void shouldRejectAnExistingDatabaseWithoutDeletingAnyFinancialHistory() {
        assertThatThrownBy(() -> StagingPhaseSevenFixture.seed(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), KEY, SECRET))
                .isInstanceOf(IllegalStateException.class).hasMessage("Fixture requires a fresh empty database");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class)).isEqualTo(2);
    }
}
