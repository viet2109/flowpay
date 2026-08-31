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
class IdempotencyMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String VALUE_TOO_LONG = "22001";
    private static final String REQUEST_HASH = "a".repeat(64);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearBusinessTables() throws SQLException {
        execute("""
                TRUNCATE TABLE idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyMigrationsThroughIdempotencySchema() throws SQLException {
        List<Integer> appliedVersions = queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """);
        List<String> columns = queryStrings("""
                SELECT column_name || ':' || data_type || ':'
                    || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'idempotency_records'
                ORDER BY ordinal_position
                """);
        List<String> indexes = queryStrings("""
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'idempotency_records'
                ORDER BY indexname
                """);

        assertThat(appliedVersions).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(columns).containsExactly(
                "id:bigint:-:NO",
                "merchant_id:bigint:-:NO",
                "operation:character varying:-:NO",
                "idempotency_key:character varying:255:NO",
                "request_hash:character:64:NO",
                "status:character varying:-:NO",
                "resource_type:character varying:-:YES",
                "resource_public_id:character varying:-:YES",
                "http_status:integer:-:YES",
                "response_payload:jsonb:-:YES",
                "created_at:timestamp with time zone:-:NO",
                "completed_at:timestamp with time zone:-:YES",
                "expires_at:timestamp with time zone:-:NO"
        );
        assertThat(indexes).contains(
                "pk_idempotency_records",
                "uq_idempotency_records_scope",
                "ix_idempotency_records_status_expires_at"
        );
    }

    @Test
    void shouldEnforceMerchantOperationAndKeyScope() throws SQLException {
        long merchantA = insertMerchant("mrc_idempotency_a");
        long merchantB = insertMerchant("mrc_idempotency_b");
        insertProcessing(merchantA, "PAYMENT_INTENT_CREATE", "shared-key", REQUEST_HASH);

        assertSqlState(UNIQUE_VIOLATION, () ->
                insertProcessing(merchantA, "PAYMENT_INTENT_CREATE", "shared-key", REQUEST_HASH));

        insertProcessing(merchantA, "PAYMENT_INTENT_CONFIRM", "shared-key", REQUEST_HASH);
        insertProcessing(merchantB, "PAYMENT_INTENT_CREATE", "shared-key", REQUEST_HASH);

        assertThat(queryLong("SELECT COUNT(*) FROM idempotency_records")).isEqualTo(3L);
    }

    @Test
    void shouldEnforceKeyLengthForeignKeyAndNoDeleteCascade() throws SQLException {
        long merchantId = insertMerchant("mrc_idempotency_constraints");
        String maximumKey = "k".repeat(255);
        insertProcessing(merchantId, "PAYMENT_INTENT_CREATE", maximumKey, REQUEST_HASH);

        assertThat(queryLong("""
                SELECT LENGTH(idempotency_key)
                FROM idempotency_records
                WHERE merchant_id = ?
                """, merchantId)).isEqualTo(255L);
        assertSqlState(VALUE_TOO_LONG, () -> insertProcessing(
                merchantId,
                "PAYMENT_INTENT_CREATE",
                "k".repeat(256),
                REQUEST_HASH
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertProcessing(
                9_999_999L,
                "PAYMENT_INTENT_CREATE",
                "invalid-merchant",
                REQUEST_HASH
        ));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM merchants WHERE id = ?", merchantId));
    }

    @Test
    void shouldEnforceFrozenOperationHashAndStatusValues() throws SQLException {
        long merchantId = insertMerchant("mrc_idempotency_checks");

        assertSqlState(CHECK_VIOLATION, () -> insertProcessing(
                merchantId,
                "REFUND_CREATE",
                "unsupported-operation",
                REQUEST_HASH
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertProcessing(
                merchantId,
                "PAYMENT_INTENT_CREATE",
                "invalid-hash",
                "A".repeat(64)
        ));
        assertSqlState(CHECK_VIOLATION, () -> execute("""
                INSERT INTO idempotency_records (
                    merchant_id, operation, idempotency_key, request_hash, status,
                    created_at, expires_at
                )
                VALUES (?, 'PAYMENT_INTENT_CREATE', 'invalid-status', ?, 'SUCCEEDED',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                """, merchantId, REQUEST_HASH));
    }

    @Test
    void shouldPersistRequestHashAndJsonbResponsePayload() throws SQLException {
        long merchantId = insertMerchant("mrc_idempotency_snapshot");
        String responsePayload = """
                {"data":{"id":"pi_snapshot","status":"CREATED"}}
                """;

        execute("""
                INSERT INTO idempotency_records (
                    merchant_id, operation, idempotency_key, request_hash, status,
                    resource_type, resource_public_id, http_status, response_payload,
                    created_at, completed_at, expires_at
                )
                VALUES (?, 'PAYMENT_INTENT_CREATE', 'snapshot-key', ?, 'COMPLETED',
                    'PAYMENT_INTENT', 'pi_snapshot', 201, CAST(? AS JSONB),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                """, merchantId, REQUEST_HASH, responsePayload);

        assertThat(queryString("""
                SELECT request_hash
                FROM idempotency_records
                WHERE merchant_id = ?
                """, merchantId)).isEqualTo(REQUEST_HASH);
        assertThat(queryString("""
                SELECT response_payload #>> '{data,id}'
                FROM idempotency_records
                WHERE merchant_id = ?
                """, merchantId)).isEqualTo("pi_snapshot");
    }

    private static long insertMerchant(String publicId) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Idempotency Merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId);
    }

    private static void insertProcessing(
            long merchantId,
            String operation,
            String key,
            String requestHash
    ) throws SQLException {
        execute("""
                INSERT INTO idempotency_records (
                    merchant_id, operation, idempotency_key, request_hash, status,
                    created_at, expires_at
                )
                VALUES (?, ?, ?, ?, 'PROCESSING',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '24 hours')
                """, merchantId, operation, key, requestHash);
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

    private static String queryString(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
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
