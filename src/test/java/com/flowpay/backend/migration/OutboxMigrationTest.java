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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class OutboxMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static long historicalPaymentCount;
    private static long historicalRefundCount;
    private static long outboxCountImmediatelyAfterV009;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrationsAcrossHistoricalData() throws SQLException {
        migrateTo("8");
        insertHistoricalSuccessRows();
        migrateTo("9");

        historicalPaymentCount = queryLong("""
                SELECT COUNT(*) FROM payment_intents WHERE status = 'SUCCEEDED'
                """);
        historicalRefundCount = queryLong("""
                SELECT COUNT(*) FROM refunds WHERE status = 'SUCCEEDED'
                """);
        outboxCountImmediatelyAfterV009 = queryLong("SELECT COUNT(*) FROM outbox_events");
    }

    @BeforeEach
    void clearOutboxEvents() throws SQLException {
        execute("TRUNCATE TABLE outbox_events RESTART IDENTITY");
    }

    @Test
    void shouldApplyCleanMigrationThroughOutboxSchema() throws SQLException {
        assertThat(queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9);

        assertThat(queryStrings("""
                SELECT column_name || ':' || data_type || ':'
                    || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'outbox_events'
                ORDER BY ordinal_position
                """)).containsExactly(
                "id:bigint:-:NO",
                "event_id:character varying:-:NO",
                "aggregate_type:character varying:-:NO",
                "aggregate_id:character varying:-:NO",
                "event_type:character varying:-:NO",
                "payload:jsonb:-:NO",
                "status:character varying:-:NO",
                "occurred_at:timestamp with time zone:-:NO",
                "available_at:timestamp with time zone:-:NO",
                "published_at:timestamp with time zone:-:YES",
                "retry_count:integer:-:NO",
                "last_error:text:-:YES",
                "created_at:timestamp with time zone:-:NO"
        );

        assertThat(queryStrings("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'outbox_events'
                ORDER BY constraint_name
                """)).contains(
                "pk_outbox_events",
                "uq_outbox_events_event_id",
                "ck_outbox_events_status",
                "ck_outbox_events_retry_count",
                "ck_outbox_events_publication_state"
        );

        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'outbox_events'
                ORDER BY indexname
                """)).containsExactlyInAnyOrder(
                "pk_outbox_events",
                "uq_outbox_events_event_id",
                "ix_outbox_events_status_available_at"
        );
        assertThat(queryStrings("""
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND indexname = 'ix_outbox_events_status_available_at'
                """)).singleElement().satisfies(
                definition -> assertThat(definition).contains("(status, available_at)")
        );
    }

    @Test
    void shouldPersistJsonPayloadMetadataAndRetryDefault() throws SQLException {
        long id = insertOutboxWithDefaultRetry(
                "ievt_payment_1",
                "PAYMENT_INTENT",
                "pi_outbox_payment",
                "payment.succeeded.v1",
                """
                        {"merchantInternalId":15,"paymentPublicId":"pi_outbox_payment",
                         "amountMinor":100000,"currency":"VND",
                         "occurredAt":"2026-09-01T11:00:00Z"}
                        """
        );

        assertThat(queryStrings("""
                SELECT event_id || ':' || aggregate_type || ':' || aggregate_id || ':'
                    || event_type || ':' || status || ':' || retry_count
                FROM outbox_events
                WHERE id = ?
                """, id)).containsExactly(
                "ievt_payment_1:PAYMENT_INTENT:pi_outbox_payment:payment.succeeded.v1:PENDING:0"
        );
        assertThat(queryStrings("""
                SELECT jsonb_typeof(payload) || ':' || (payload ->> 'paymentPublicId')
                FROM outbox_events
                WHERE id = ?
                """, id)).containsExactly("object:pi_outbox_payment");
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM outbox_events
                WHERE id = ?
                  AND occurred_at = available_at
                  AND occurred_at = created_at
                  AND published_at IS NULL
                  AND last_error IS NULL
                """, id)).isOne();
    }

    @Test
    void shouldEnforceUniqueEventIdentity() throws SQLException {
        insertOutbox(
                "ievt_duplicate",
                "PAYMENT_INTENT",
                "pi_duplicate_1",
                "payment.succeeded.v1",
                "{}",
                "PENDING",
                null,
                0,
                null
        );

        assertSqlState(UNIQUE_VIOLATION, () -> insertOutbox(
                "ievt_duplicate",
                "REFUND",
                "re_duplicate_2",
                "refund.succeeded.v1",
                "{}",
                "PENDING",
                null,
                0,
                null
        ));
    }

    @Test
    void shouldEnforceRetryablePublicationStateConsistency() throws SQLException {
        insertOutbox(
                "ievt_pending",
                "PAYMENT_INTENT",
                "pi_pending",
                "payment.succeeded.v1",
                "{}",
                "PENDING",
                null,
                0,
                null
        );
        insertOutbox(
                "ievt_failed",
                "PAYMENT_INTENT",
                "pi_failed",
                "payment.succeeded.v1",
                "{}",
                "FAILED",
                null,
                2,
                "Broker unavailable"
        );
        insertOutbox(
                "ievt_published",
                "REFUND",
                "re_published",
                "refund.succeeded.v1",
                "{}",
                "PUBLISHED",
                OffsetDateTime.parse("2026-09-01T11:05:00Z"),
                1,
                null
        );

        assertThat(queryLong("SELECT COUNT(*) FROM outbox_events")).isEqualTo(3L);
        assertSqlState(CHECK_VIOLATION, () -> insertOutbox(
                "ievt_invalid_status", "PAYMENT_INTENT", "pi_invalid_status",
                "payment.succeeded.v1", "{}", "DEAD", null, 0, null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertOutbox(
                "ievt_negative_retry", "PAYMENT_INTENT", "pi_negative_retry",
                "payment.succeeded.v1", "{}", "FAILED", null, -1, null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertOutbox(
                "ievt_published_without_time", "PAYMENT_INTENT", "pi_no_time",
                "payment.succeeded.v1", "{}", "PUBLISHED", null, 0, null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertOutbox(
                "ievt_pending_with_time", "PAYMENT_INTENT", "pi_pending_time",
                "payment.succeeded.v1", "{}", "PENDING",
                OffsetDateTime.parse("2026-09-01T11:00:00Z"), 0, null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertOutbox(
                "ievt_failed_with_time", "REFUND", "re_failed_time",
                "refund.succeeded.v1", "{}", "FAILED",
                OffsetDateTime.parse("2026-09-01T11:00:00Z"), 1, "Failure"
        ));
    }

    @Test
    void shouldAllowFutureEventTypesWithoutAggregateForeignKeys() throws SQLException {
        insertOutbox(
                "ievt_future_contract",
                "FUTURE_AGGREGATE",
                "future_public_id",
                "future.contract.v99",
                "{\"contract\":\"future\"}",
                "PENDING",
                null,
                0,
                null
        );

        assertThat(queryStrings("SELECT event_type FROM outbox_events"))
                .containsExactly("future.contract.v99");
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'outbox_events'
                  AND constraint_type = 'FOREIGN KEY'
                """)).isZero();
    }

    @Test
    void shouldNotBackfillHistoricalPaymentRefundOrLedgerEvents() {
        assertThat(historicalPaymentCount).isOne();
        assertThat(historicalRefundCount).isOne();
        assertThat(outboxCountImmediatelyAfterV009).isZero();
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion(target))
                .load()
                .migrate();
    }

    private static void insertHistoricalSuccessRows() throws SQLException {
        long merchantId = insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES ('mrc_outbox_history', 'Outbox History', 'ACTIVE',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """);
        long paymentIntentId = insertAndReturnId("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    created_at, updated_at
                )
                VALUES ('pi_outbox_history', ?, 100000, 'VND', 'SUCCEEDED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, merchantId);
        insertAndReturnId("""
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor,
                    currency, status, provider, provider_refund_id,
                    created_at, updated_at, completed_at
                )
                VALUES ('re_outbox_history', ?, ?, 25000, 'VND', 'SUCCEEDED',
                    'SIMULATOR', 'sim_re_outbox_history', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, merchantId, paymentIntentId);
    }

    private static long insertOutboxWithDefaultRetry(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    status, occurred_at, available_at, created_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS JSONB), 'PENDING',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, eventId, aggregateType, aggregateId, eventType, payload);
    }

    private static long insertOutbox(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String payload,
            String status,
            OffsetDateTime publishedAt,
            int retryCount,
            String lastError
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO outbox_events (
                    event_id, aggregate_type, aggregate_id, event_type, payload,
                    status, occurred_at, available_at, published_at, retry_count,
                    last_error, created_at
                )
                VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                eventId,
                aggregateType,
                aggregateId,
                eventType,
                payload,
                status,
                publishedAt,
                retryCount,
                lastError);
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
