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
class PaymentMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("5"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearBusinessTables() throws SQLException {
        execute("""
                TRUNCATE TABLE payment_transactions, payment_intents, refresh_tokens,
                    merchant_api_keys, merchant_members, merchants, users
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyMigrationsThroughPaymentSchema() throws SQLException {
        List<Integer> appliedVersions = queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """);
        List<String> paymentTables = queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('payment_intents', 'payment_transactions')
                ORDER BY table_name
                """);
        List<String> paymentIndexes = queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('payment_intents', 'payment_transactions')
                ORDER BY indexname
                """);

        assertThat(appliedVersions).containsExactly(1, 2, 3, 4, 5);
        assertThat(paymentTables).containsExactly("payment_intents", "payment_transactions");
        assertThat(paymentIndexes).contains(
                "uq_payment_intents_public_id",
                "ix_payment_intents_merchant_created_at",
                "ix_payment_intents_merchant_status_created_at",
                "ix_payment_intents_merchant_order_id",
                "uq_payment_transactions_public_id",
                "uq_payment_transactions_intent_attempt"
        );
    }

    @Test
    void shouldApplyFinancialDefaultsAndAllowRepeatedMerchantOrderId() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_defaults");
        long firstPaymentId = insertPaymentIntentUsingDefaults(
                "pi_defaults_1",
                merchantId,
                "ORDER-SHARED",
                10_000L
        );
        insertPaymentIntentUsingDefaults("pi_defaults_2", merchantId, "ORDER-SHARED", 20_000L);

        assertThat(queryLongs("""
                SELECT refunded_amount_minor, refund_reserved_minor, version
                FROM payment_intents
                WHERE id = ?
                """, firstPaymentId)).containsExactly(0L, 0L, 0L);
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM payment_intents
                WHERE merchant_id = ? AND merchant_order_id = 'ORDER-SHARED'
                """, merchantId)).isEqualTo(2L);
    }

    @Test
    void shouldRejectNonPositivePaymentAmounts() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_amount");

        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_zero",
                merchantId,
                0L,
                0L,
                0L,
                "CREATED"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_negative",
                merchantId,
                -1L,
                0L,
                0L,
                "CREATED"
        ));
    }

    @Test
    void shouldRejectInvalidRefundAccounting() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_refunds");

        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_negative_refunded",
                merchantId,
                100L,
                -1L,
                0L,
                "CREATED"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_negative_reserved",
                merchantId,
                100L,
                0L,
                -1L,
                "CREATED"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_refund_overflow",
                merchantId,
                100L,
                60L,
                41L,
                "CREATED"
        ));
    }

    @Test
    void shouldRejectDuplicatePaymentAndTransactionPublicIds() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_public_ids");
        long paymentId = insertPaymentIntent(
                "pi_duplicate",
                merchantId,
                100L,
                0L,
                0L,
                "CREATED"
        );
        insertPaymentTransaction("ptxn_duplicate", paymentId, 1, "PROCESSING");

        assertSqlState(UNIQUE_VIOLATION, () -> insertPaymentIntent(
                "pi_duplicate",
                merchantId,
                200L,
                0L,
                0L,
                "CREATED"
        ));
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertPaymentTransaction("ptxn_duplicate", paymentId, 2, "PROCESSING"));
    }

    @Test
    void shouldRejectDuplicateAndNonPositiveAttemptNumbers() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_attempts");
        long paymentId = insertPaymentIntent(
                "pi_attempts",
                merchantId,
                100L,
                0L,
                0L,
                "PROCESSING"
        );
        insertPaymentTransaction("ptxn_attempt_1", paymentId, 1, "PROCESSING");

        assertSqlState(UNIQUE_VIOLATION,
                () -> insertPaymentTransaction("ptxn_attempt_duplicate", paymentId, 1, "PROCESSING"));
        assertSqlState(CHECK_VIOLATION,
                () -> insertPaymentTransaction("ptxn_attempt_zero", paymentId, 0, "PROCESSING"));
    }

    @Test
    void shouldEnforcePaymentForeignKeys() throws SQLException {
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertPaymentIntent(
                "pi_invalid_merchant",
                9_999_999L,
                100L,
                0L,
                0L,
                "CREATED"
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertPaymentTransaction("ptxn_invalid_payment", 9_999_999L, 1, "PROCESSING"));
    }

    @Test
    void shouldRejectUnknownPaymentStatuses() throws SQLException {
        long merchantId = insertMerchant("mrc_payment_status");

        assertSqlState(CHECK_VIOLATION, () -> insertPaymentIntent(
                "pi_bad_status",
                merchantId,
                100L,
                0L,
                0L,
                "UNKNOWN"
        ));

        long paymentId = insertPaymentIntent(
                "pi_valid_status",
                merchantId,
                100L,
                0L,
                0L,
                "PROCESSING"
        );
        assertSqlState(CHECK_VIOLATION,
                () -> insertPaymentTransaction("ptxn_bad_status", paymentId, 1, "CREATED"));
    }

    private static long insertMerchant(String publicId) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Payment Merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId);
    }

    private static long insertPaymentIntentUsingDefaults(
            String publicId,
            long merchantId,
            String merchantOrderId,
            long amountMinor
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, merchant_order_id, description,
                    amount_minor, currency, status, created_at, updated_at
                )
                VALUES (?, ?, ?, 'Test payment', ?, 'VND', 'CREATED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId, merchantId, merchantOrderId, amountMinor);
    }

    private static long insertPaymentIntent(
            String publicId,
            long merchantId,
            long amountMinor,
            long refundedAmountMinor,
            long refundReservedMinor,
            String status
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor, created_at, updated_at
                )
                VALUES (?, ?, ?, 'VND', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                publicId,
                merchantId,
                amountMinor,
                status,
                refundedAmountMinor,
                refundReservedMinor);
    }

    private static long insertPaymentTransaction(
            String publicId,
            long paymentIntentId,
            int attemptNo,
            String status
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO payment_transactions (
                    public_id, payment_intent_id, attempt_no, provider, status,
                    started_at, created_at, updated_at
                )
                VALUES (?, ?, ?, 'SIMULATOR', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId, paymentIntentId, attemptNo, status);
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

    private static List<Long> queryLongs(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                List<Long> values = new ArrayList<>(resultSet.getMetaData().getColumnCount());
                for (int column = 1; column <= resultSet.getMetaData().getColumnCount(); column++) {
                    values.add(resultSet.getLong(column));
                }
                return values;
            }
        }
    }

    private static List<Integer> queryIntegers(String sql) throws SQLException {
        List<Integer> values = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getInt(1));
            }
        }
        return values;
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
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

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }
}
