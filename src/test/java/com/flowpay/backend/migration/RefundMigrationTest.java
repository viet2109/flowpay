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
class RefundMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("7"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearBusinessTables() throws SQLException {
        execute("""
                TRUNCATE TABLE refunds, idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyCleanMigrationThroughRefundSchema() throws SQLException {
        assertThat(queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """)).containsExactly(1, 2, 3, 4, 5, 6, 7);

        assertThat(queryStrings("""
                SELECT column_name || ':' || data_type || ':'
                    || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'refunds'
                ORDER BY ordinal_position
                """)).containsExactly(
                "id:bigint:-:NO",
                "public_id:character varying:-:NO",
                "merchant_id:bigint:-:NO",
                "payment_intent_id:bigint:-:NO",
                "amount_minor:bigint:-:NO",
                "currency:character:3:NO",
                "status:character varying:-:NO",
                "reason:character varying:255:YES",
                "provider:character varying:-:NO",
                "provider_refund_id:character varying:-:YES",
                "failure_code:character varying:-:YES",
                "failure_message:character varying:-:YES",
                "created_at:timestamp with time zone:-:NO",
                "updated_at:timestamp with time zone:-:NO",
                "completed_at:timestamp with time zone:-:YES",
                "version:bigint:-:NO"
        );

        assertThat(queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'refunds'
                ORDER BY indexname
                """)).containsExactlyInAnyOrder(
                "pk_refunds",
                "uq_refunds_public_id",
                "ix_refunds_payment_intent_created_at",
                "ix_refunds_merchant_created_at",
                "ix_refunds_merchant_status_created_at"
        );

        assertThat(queryStrings("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'refunds'
                ORDER BY constraint_name
                """)).contains(
                "pk_refunds",
                "uq_refunds_public_id",
                "fk_refunds_merchant",
                "fk_refunds_payment_intent",
                "ck_refunds_amount_positive"
        );
    }

    @Test
    void shouldRejectNonPositiveRefundAmounts() throws SQLException {
        RefundOwner owner = insertOwner("amount");

        assertSqlState(CHECK_VIOLATION, () -> insertRefund(
                "re_zero", owner, 0L, "PROCESSING", null, null, null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertRefund(
                "re_negative", owner, -1L, "PROCESSING", null, null, null
        ));
    }

    @Test
    void shouldEnforcePublicIdAndOwnershipForeignKeysWithoutDeleteCascade() throws SQLException {
        RefundOwner owner = insertOwner("constraints");
        insertRefund("re_duplicate", owner, 1_000L, "PROCESSING", null, null, null);

        assertSqlState(UNIQUE_VIOLATION, () -> insertRefund(
                "re_duplicate", owner, 2_000L, "PROCESSING", null, null, null
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertRefund(
                "re_invalid_merchant",
                new RefundOwner(9_999_999L, owner.paymentIntentId()),
                1_000L,
                "PROCESSING",
                null,
                null,
                null
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertRefund(
                "re_invalid_payment",
                new RefundOwner(owner.merchantId(), 9_999_999L),
                1_000L,
                "PROCESSING",
                null,
                null,
                null
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM payment_intents WHERE id = ?", owner.paymentIntentId()));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM merchants WHERE id = ?", owner.merchantId()));
    }

    @Test
    void shouldPersistProviderAndFailureMetadataWithNullableProviderRefundId() throws SQLException {
        RefundOwner owner = insertOwner("metadata");
        long processingId = insertRefund(
                "re_processing", owner, 1_000L, "PROCESSING", null, null, null
        );
        long succeededId = insertRefund(
                "re_succeeded", owner, 2_000L, "SUCCEEDED", "sim_re_123", null, null
        );
        long failedId = insertRefund(
                "re_failed", owner, 3_000L, "FAILED", null, "DECLINED", "Refund declined"
        );

        assertThat(queryStrings("""
                SELECT provider || ':' || COALESCE(provider_refund_id, '<null>')
                FROM refunds
                WHERE id IN (?, ?)
                ORDER BY id
                """, processingId, succeededId)).containsExactly(
                "SIMULATOR:<null>",
                "SIMULATOR:sim_re_123"
        );
        assertThat(queryStrings("""
                SELECT failure_code || ':' || failure_message
                FROM refunds
                WHERE id = ?
                """, failedId)).containsExactly("DECLINED:Refund declined");
    }

    @Test
    void shouldPreservePaymentRefundTotalConstraint() throws SQLException {
        RefundOwner owner = insertOwner("payment_guard");

        assertSqlState(CHECK_VIOLATION, () -> execute("""
                UPDATE payment_intents
                SET refunded_amount_minor = 60000,
                    refund_reserved_minor = 41000
                WHERE id = ?
                """, owner.paymentIntentId()));
    }

    @Test
    void shouldAcceptExactlyTheApprovedIdempotencyOperations() throws SQLException {
        long merchantId = insertMerchant("mrc_refund_operations");

        insertIdempotencyRecord(merchantId, "PAYMENT_INTENT_CREATE", "create-payment");
        insertIdempotencyRecord(merchantId, "PAYMENT_INTENT_CONFIRM", "confirm-payment");
        insertIdempotencyRecord(merchantId, "REFUND_CREATE", "create-refund");

        assertThat(queryLong("SELECT COUNT(*) FROM idempotency_records")).isEqualTo(3L);
        assertSqlState(CHECK_VIOLATION,
                () -> insertIdempotencyRecord(merchantId, "REFUND_CONFIRM", "unsupported"));
    }

    private static RefundOwner insertOwner(String suffix) throws SQLException {
        long merchantId = insertMerchant("mrc_refund_" + suffix);
        long paymentIntentId = insertAndReturnId("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    created_at, updated_at
                )
                VALUES (?, ?, 100000, 'VND', 'SUCCEEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, "pi_refund_" + suffix, merchantId);
        return new RefundOwner(merchantId, paymentIntentId);
    }

    private static long insertMerchant(String publicId) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Refund Merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId);
    }

    private static long insertRefund(
            String publicId,
            RefundOwner owner,
            long amountMinor,
            String status,
            String providerRefundId,
            String failureCode,
            String failureMessage
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor, currency,
                    status, reason, provider, provider_refund_id, failure_code,
                    failure_message, created_at, updated_at, completed_at
                )
                VALUES (?, ?, ?, ?, 'VND', ?, 'CUSTOMER_REQUEST', 'SIMULATOR', ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    CASE WHEN ? IN ('SUCCEEDED', 'FAILED') THEN CURRENT_TIMESTAMP ELSE NULL END)
                RETURNING id
                """,
                publicId,
                owner.merchantId(),
                owner.paymentIntentId(),
                amountMinor,
                status,
                providerRefundId,
                failureCode,
                failureMessage,
                status);
    }

    private static void insertIdempotencyRecord(
            long merchantId,
            String operation,
            String idempotencyKey
    ) throws SQLException {
        execute("""
                INSERT INTO idempotency_records (
                    merchant_id, operation, idempotency_key, request_hash, status,
                    created_at, expires_at
                )
                VALUES (?, ?, ?, ?, 'PROCESSING',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                """, merchantId, operation, idempotencyKey, REQUEST_HASH);
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

    private static List<Integer> queryIntegers(String sql, Object... parameters) throws SQLException {
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

    private static List<String> queryStrings(String sql, Object... parameters) throws SQLException {
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

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
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

    private record RefundOwner(long merchantId, long paymentIntentId) {
    }

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }
}
