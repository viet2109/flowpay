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
class IdentityMerchantMigrationTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("4"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearBusinessTables() throws SQLException {
        execute("""
                TRUNCATE TABLE refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyAllIdentityAndMerchantMigrations() throws SQLException {
        List<Integer> appliedVersions = queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """);
        List<String> phaseOneTables = queryStrings("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'users', 'merchants', 'merchant_members', 'merchant_api_keys', 'refresh_tokens'
                  )
                ORDER BY table_name
                """);

        assertThat(appliedVersions).containsExactly(1, 2, 3, 4);
        assertThat(phaseOneTables).containsExactly(
                "merchant_api_keys",
                "merchant_members",
                "merchants",
                "refresh_tokens",
                "users"
        );
    }

    @Test
    void shouldRejectDuplicateNormalizedEmail() throws SQLException {
        insertUser("usr_email_1", "Owner@Example.com");

        assertSqlState(UNIQUE_VIOLATION,
                () -> insertUser("usr_email_2", "owner@example.com"));
    }

    @Test
    void shouldRejectDuplicateMerchantMembership() throws SQLException {
        long userId = insertUser("usr_member", "member@example.com");
        long merchantId = insertMerchant("mrc_member");
        insertMembership(merchantId, userId);

        assertSqlState(UNIQUE_VIOLATION, () -> insertMembership(merchantId, userId));
    }

    @Test
    void shouldRejectDuplicateApiKeyPrefix() throws SQLException {
        long merchantId = insertMerchant("mrc_api_key");
        insertApiKey("key_1", merchantId, "fp_test_shared", "hash-1");

        assertSqlState(UNIQUE_VIOLATION,
                () -> insertApiKey("key_2", merchantId, "fp_test_shared", "hash-2"));
    }

    @Test
    void shouldEnforceAllPhaseOneForeignKeys() throws SQLException {
        long userId = insertUser("usr_fk", "fk@example.com");
        long merchantId = insertMerchant("mrc_fk");

        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertMembership(9_999_999L, userId));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertMembership(merchantId, 9_999_999L));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertApiKey("key_fk", 9_999_999L, "fp_test_fk", "hash-fk"));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertRefreshToken(9_999_999L, "refresh-invalid-user", null));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertRefreshToken(userId, "refresh-invalid-replacement", 9_999_999L));
    }

    @Test
    void shouldRejectDuplicateRefreshTokenDigest() throws SQLException {
        long userId = insertUser("usr_refresh", "refresh@example.com");
        insertRefreshToken(userId, "same-refresh-digest", null);

        assertSqlState(UNIQUE_VIOLATION,
                () -> insertRefreshToken(userId, "same-refresh-digest", null));
    }

    private static long insertUser(String publicId, String email) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO users (
                    public_id, email, password_hash, first_name, last_name,
                    status, created_at, updated_at
                )
                VALUES (?, ?, 'password-hash', 'Test', 'User', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId, email);
    }

    private static long insertMerchant(String publicId) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Test Merchant', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """, publicId);
    }

    private static void insertMembership(long merchantId, long userId) throws SQLException {
        execute("""
                INSERT INTO merchant_members (merchant_id, user_id, role, created_at)
                VALUES (?, ?, 'OWNER', CURRENT_TIMESTAMP)
                """, merchantId, userId);
    }

    private static void insertApiKey(
            String publicId,
            long merchantId,
            String keyPrefix,
            String keyHash
    ) throws SQLException {
        execute("""
                INSERT INTO merchant_api_keys (
                    public_id, merchant_id, name, key_prefix, key_hash, status, created_at
                )
                VALUES (?, ?, 'Test key', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, publicId, merchantId, keyPrefix, keyHash);
    }

    private static void insertRefreshToken(long userId, String tokenHash, Long replacementId)
            throws SQLException {
        execute("""
                INSERT INTO refresh_tokens (
                    user_id, token_hash, expires_at, created_at, replaced_by_id
                )
                VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days', CURRENT_TIMESTAMP, ?)
                """, userId, tokenHash, replacementId);
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
