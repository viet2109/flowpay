package com.flowpay.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WebhookConfigurationMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private static final List<String> SUPPORTED_EVENT_TYPES = List.of(
            "payment.processing",
            "payment.succeeded",
            "payment.failed",
            "refund.processing",
            "refund.succeeded",
            "refund.failed"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearWebhookConfiguration() throws SQLException {
        execute("""
                TRUNCATE TABLE webhook_endpoint_events, webhook_endpoints, merchants
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyCleanMigrationThroughWebhookConfigurationSchema() throws SQLException {
        assertThat(queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        assertThat(queryStrings("""
                SELECT column_name || ':' || data_type || ':'
                    || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'webhook_endpoints'
                ORDER BY ordinal_position
                """)).containsExactly(
                "id:bigint:-:NO",
                "public_id:character varying:-:NO",
                "merchant_id:bigint:-:NO",
                "url:character varying:2048:NO",
                "secret_ciphertext:text:-:NO",
                "status:character varying:-:NO",
                "created_at:timestamp with time zone:-:NO",
                "updated_at:timestamp with time zone:-:NO",
                "version:bigint:-:NO"
        );
        assertThat(queryStrings("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'webhook_endpoint_events'
                ORDER BY ordinal_position
                """)).containsExactly(
                "endpoint_id:bigint:NO",
                "event_type:character varying:NO"
        );

        assertThat(queryStrings("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name IN ('webhook_endpoints', 'webhook_endpoint_events')
                  AND constraint_name NOT LIKE '%not_null'
                ORDER BY constraint_name
                """)).containsExactlyInAnyOrder(
                "pk_webhook_endpoints",
                "uq_webhook_endpoints_public_id",
                "fk_webhook_endpoints_merchant",
                "ck_webhook_endpoints_status",
                "pk_webhook_endpoint_events",
                "fk_webhook_endpoint_events_endpoint",
                "ck_webhook_endpoint_events_event_type"
        );
        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('webhook_endpoints', 'webhook_endpoint_events')
                ORDER BY indexname
                """)).containsExactlyInAnyOrder(
                "pk_webhook_endpoints",
                "uq_webhook_endpoints_public_id",
                "ix_webhook_endpoints_merchant_status_created_at",
                "pk_webhook_endpoint_events"
        );
        assertThat(queryStrings("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'ix_webhook_endpoints_merchant_status_created_at'
                """)).singleElement().satisfies(
                definition -> assertThat(definition)
                        .contains("(merchant_id, status, created_at DESC)")
        );

        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'webhook_endpoints'
                  AND column_name IN ('secret', 'raw_secret')
                """)).isZero();
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                      'webhook_events',
                      'webhook_deliveries',
                      'webhook_delivery_attempts'
                  )
                """)).isZero();
        assertThat(queryStrings("SELECT to_regclass('public.outbox_events')::text"))
                .containsExactly("outbox_events");
    }

    @Test
    void shouldEnforceEndpointIdentityOwnershipAndStatusWithoutDeleteCascade()
            throws SQLException {
        long merchantId = insertMerchant("mrc_webhook_constraints");
        long endpointId = insertEndpoint(
                "wep_constraints",
                merchantId,
                "https://merchant.example/webhooks",
                "ACTIVE"
        );

        assertSqlState(UNIQUE_VIOLATION, () -> insertEndpoint(
                "wep_constraints",
                merchantId,
                "https://merchant.example/another-webhook",
                "ACTIVE"
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertEndpoint(
                "wep_unknown_merchant",
                9_999_999L,
                "https://merchant.example/unknown",
                "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertEndpoint(
                "wep_invalid_status",
                merchantId,
                "https://merchant.example/invalid-status",
                "PENDING"
        ));

        insertSubscription(endpointId, "payment.succeeded");
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM webhook_endpoints WHERE id = ?", endpointId));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM merchants WHERE id = ?", merchantId));
    }

    @Test
    void shouldAcceptExactlySixSubscriptionTypesAndRejectDuplicates() throws SQLException {
        long merchantId = insertMerchant("mrc_webhook_events");
        long endpointId = insertEndpoint(
                "wep_events",
                merchantId,
                "https://merchant.example/events",
                "DISABLED"
        );

        for (String eventType : SUPPORTED_EVENT_TYPES) {
            insertSubscription(endpointId, eventType);
        }

        assertThat(queryStrings("""
                SELECT event_type
                FROM webhook_endpoint_events
                WHERE endpoint_id = ?
                ORDER BY event_type
                """, endpointId)).containsExactlyElementsOf(
                SUPPORTED_EVENT_TYPES.stream().sorted().toList()
        );
        assertSqlState(CHECK_VIOLATION,
                () -> insertSubscription(endpointId, "payment.cancelled"));
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertSubscription(endpointId, "payment.succeeded"));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertSubscription(9_999_999L, "payment.succeeded"));
    }

    @Test
    void shouldAllowDuplicateUrlsWithinAndAcrossMerchants() throws SQLException {
        long firstMerchantId = insertMerchant("mrc_webhook_url_1");
        long secondMerchantId = insertMerchant("mrc_webhook_url_2");
        String sharedUrl = "https://merchant.example/shared";

        insertEndpoint("wep_url_1", firstMerchantId, sharedUrl, "ACTIVE");
        insertEndpoint("wep_url_2", firstMerchantId, sharedUrl, "ACTIVE");
        insertEndpoint("wep_url_3", secondMerchantId, sharedUrl, "DISABLED");

        assertThat(queryLong("SELECT COUNT(*) FROM webhook_endpoints WHERE url = ?", sharedUrl))
                .isEqualTo(3L);
    }

    private static long insertMerchant(String publicId) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Webhook Merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId);
    }

    private static long insertEndpoint(
            String publicId,
            long merchantId,
            String url,
            String status
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO webhook_endpoints (
                    public_id, merchant_id, url, secret_ciphertext, status,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, 'v1:test-iv:test-ciphertext', ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId, merchantId, url, status);
    }

    private static void insertSubscription(long endpointId, String eventType)
            throws SQLException {
        execute("""
                INSERT INTO webhook_endpoint_events (endpoint_id, event_type)
                VALUES (?, ?)
                """, endpointId, eventType);
    }

    private static long insertAndReturnId(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static long queryLong(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private static List<Integer> queryIntegers(String sql, Object... parameters)
            throws SQLException {
        List<Integer> values = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getInt(1));
                }
            }
        }
        return values;
    }

    private static List<String> queryStrings(String sql, Object... parameters)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
            }
        }
        return values;
    }

    private static void bind(PreparedStatement statement, Object... parameters)
            throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private static void assertSqlState(String expectedState, SqlOperation operation) {
        assertThatThrownBy(operation::execute)
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(expectedState)
                );
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }
}
