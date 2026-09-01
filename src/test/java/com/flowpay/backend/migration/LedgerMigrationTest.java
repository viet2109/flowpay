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
class LedgerMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String NOT_NULL_VIOLATION = "23502";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @BeforeAll
    static void applyMigrations() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("8"))
                .load()
                .migrate();
    }

    @BeforeEach
    void clearLedgerTables() throws SQLException {
        execute("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyCleanMigrationThroughLedgerSchema() throws SQLException {
        assertThat(queryIntegers("""
                SELECT version::integer
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);

        assertColumns("ledger_accounts", List.of(
                "id:bigint:-:NO",
                "public_id:character varying:-:NO",
                "account_code:character varying:-:NO",
                "account_type:character varying:-:NO",
                "owner_type:character varying:-:NO",
                "owner_id:bigint:-:YES",
                "currency:character:3:NO",
                "status:character varying:-:NO",
                "created_at:timestamp with time zone:-:NO"
        ));
        assertColumns("ledger_transactions", List.of(
                "id:bigint:-:NO",
                "public_id:character varying:-:NO",
                "posting_type:character varying:-:NO",
                "reference_type:character varying:-:NO",
                "reference_id:character varying:-:NO",
                "currency:character:3:NO",
                "description:character varying:-:YES",
                "occurred_at:timestamp with time zone:-:NO",
                "created_at:timestamp with time zone:-:NO"
        ));
        assertColumns("ledger_entries", List.of(
                "id:bigint:-:NO",
                "ledger_transaction_id:bigint:-:NO",
                "ledger_account_id:bigint:-:NO",
                "entry_no:integer:-:NO",
                "direction:character varying:-:NO",
                "amount_minor:bigint:-:NO",
                "created_at:timestamp with time zone:-:NO"
        ));

        assertConstraints("ledger_accounts", List.of(
                "pk_ledger_accounts",
                "uq_ledger_accounts_public_id",
                "uq_ledger_accounts_account_code",
                "ck_ledger_accounts_type",
                "ck_ledger_accounts_owner_type",
                "ck_ledger_accounts_status",
                "ck_ledger_accounts_owner"
        ));
        assertConstraints("ledger_transactions", List.of(
                "pk_ledger_transactions",
                "uq_ledger_transactions_public_id",
                "uq_ledger_transactions_business_reference",
                "ck_ledger_transactions_posting_type",
                "ck_ledger_transactions_reference_type",
                "ck_ledger_transactions_reference"
        ));
        assertConstraints("ledger_entries", List.of(
                "pk_ledger_entries",
                "fk_ledger_entries_transaction",
                "fk_ledger_entries_account",
                "uq_ledger_entries_transaction_entry_no",
                "ck_ledger_entries_entry_no_positive",
                "ck_ledger_entries_direction",
                "ck_ledger_entries_amount_positive"
        ));

        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('outbox_events', 'webhook_events')
                """)).isZero();
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'ledger_accounts'
                  AND column_name = 'balance_minor'
                """)).isZero();
        assertThat(queryLong("""
                SELECT COUNT(*)
                FROM information_schema.triggers
                WHERE trigger_schema = 'public'
                  AND event_object_table IN (
                      'ledger_accounts', 'ledger_transactions', 'ledger_entries'
                  )
                """)).isZero();
    }

    @Test
    void shouldEnforceCanonicalLedgerAccountIdentity() throws SQLException {
        insertAccount(
                "la_system_vnd",
                "SYSTEM_CLEARING:VND",
                "SYSTEM_CLEARING",
                "SYSTEM",
                null,
                "VND",
                "ACTIVE"
        );
        insertAccount(
                "la_merchant_vnd",
                "MERCHANT_PAYABLE:15:VND",
                "MERCHANT_PAYABLE",
                "MERCHANT",
                15L,
                "VND",
                "ACTIVE"
        );

        assertSqlState(UNIQUE_VIOLATION, () -> insertAccount(
                "la_system_vnd",
                "SYSTEM_CLEARING:USD",
                "SYSTEM_CLEARING",
                "SYSTEM",
                null,
                "USD",
                "ACTIVE"
        ));
        assertSqlState(UNIQUE_VIOLATION, () -> insertAccount(
                "la_duplicate_code",
                "SYSTEM_CLEARING:VND",
                "SYSTEM_CLEARING",
                "SYSTEM",
                null,
                "VND",
                "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_invalid_type", "FEE_REVENUE:VND", "FEE_REVENUE",
                "SYSTEM", null, "VND", "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_invalid_owner_type", "SYSTEM_INVALID_OWNER:VND", "SYSTEM_CLEARING",
                "PLATFORM", null, "VND", "ACTIVE"
        ));
        assertSqlState(NOT_NULL_VIOLATION, () -> insertAccount(
                "la_null_owner_type", "SYSTEM_NULL_OWNER:VND", "SYSTEM_CLEARING",
                null, null, "VND", "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_invalid_status", "SYSTEM_INVALID_STATUS:VND", "SYSTEM_CLEARING",
                "SYSTEM", null, "VND", "PENDING"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_system_with_owner", "SYSTEM_WITH_OWNER:VND", "SYSTEM_CLEARING",
                "SYSTEM", 15L, "VND", "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_merchant_without_owner", "MERCHANT_WITHOUT_OWNER:VND", "MERCHANT_PAYABLE",
                "MERCHANT", null, "VND", "ACTIVE"
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertAccount(
                "la_merchant_invalid_owner", "MERCHANT_INVALID_OWNER:VND", "MERCHANT_PAYABLE",
                "MERCHANT", 0L, "VND", "ACTIVE"
        ));

        assertThat(queryLong("SELECT COUNT(*) FROM ledger_accounts")).isEqualTo(2L);
    }

    @Test
    void shouldEnforcePostingAndReferenceIdentity() throws SQLException {
        insertTransaction(
                "ltxn_payment",
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_ledger_payment",
                "VND",
                "Payment succeeded"
        );
        insertTransaction(
                "ltxn_refund",
                "REFUND_SUCCEEDED",
                "REFUND",
                "re_ledger_refund",
                "VND",
                "Refund succeeded"
        );
        insertTransaction(
                "ltxn_reversal",
                "REVERSAL",
                "LEDGER_TRANSACTION",
                "ltxn_original",
                "VND",
                null
        );

        assertSqlState(UNIQUE_VIOLATION, () -> insertTransaction(
                "ltxn_payment",
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_other",
                "VND",
                null
        ));
        assertSqlState(UNIQUE_VIOLATION, () -> insertTransaction(
                "ltxn_duplicate_reference",
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_ledger_payment",
                "USD",
                null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertTransaction(
                "ltxn_invalid_posting",
                "PAYMENT_FAILED",
                "PAYMENT_INTENT",
                "pi_failed",
                "VND",
                null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertTransaction(
                "ltxn_invalid_reference",
                "PAYMENT_SUCCEEDED",
                "PAYMENT",
                "pi_invalid_reference",
                "VND",
                null
        ));
        assertSqlState(CHECK_VIOLATION, () -> insertTransaction(
                "ltxn_invalid_pair",
                "PAYMENT_SUCCEEDED",
                "REFUND",
                "re_invalid_pair",
                "VND",
                null
        ));

        assertThat(queryLong("SELECT COUNT(*) FROM ledger_transactions")).isEqualTo(3L);
    }

    @Test
    void shouldEnforceEntryConstraintsAndForeignKeysWithoutDeleteCascade()
            throws SQLException {
        long clearingId = insertAccount(
                "la_entry_clearing", "SYSTEM_CLEARING:VND", "SYSTEM_CLEARING",
                "SYSTEM", null, "VND", "ACTIVE"
        );
        long payableId = insertAccount(
                "la_entry_payable", "MERCHANT_PAYABLE:21:VND", "MERCHANT_PAYABLE",
                "MERCHANT", 21L, "VND", "ACTIVE"
        );
        long transactionId = insertTransaction(
                "ltxn_entries",
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_ledger_entries",
                "VND",
                null
        );
        insertEntry(transactionId, clearingId, 1, "DEBIT", 1_000L);
        insertEntry(transactionId, payableId, 2, "CREDIT", 1_000L);

        assertSqlState(CHECK_VIOLATION,
                () -> insertEntry(transactionId, clearingId, 3, "DEBIT", 0L));
        assertSqlState(CHECK_VIOLATION,
                () -> insertEntry(transactionId, clearingId, 3, "DEBIT", -1L));
        assertSqlState(CHECK_VIOLATION,
                () -> insertEntry(transactionId, clearingId, 3, "INCREASE", 1L));
        assertSqlState(CHECK_VIOLATION,
                () -> insertEntry(transactionId, clearingId, 0, "DEBIT", 1L));
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertEntry(transactionId, payableId, 1, "CREDIT", 1L));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertEntry(9_999_999L, clearingId, 1, "DEBIT", 1L));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertEntry(transactionId, 9_999_999L, 3, "CREDIT", 1L));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM ledger_transactions WHERE id = ?", transactionId));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> execute("DELETE FROM ledger_accounts WHERE id = ?", clearingId));

        assertThat(queryLong("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(2L);
    }

    @Test
    void shouldPersistValidPaymentAndRefundStyleRows() throws SQLException {
        long clearingId = insertAccount(
                "la_flow_clearing", "SYSTEM_CLEARING:VND", "SYSTEM_CLEARING",
                "SYSTEM", null, "VND", "ACTIVE"
        );
        long payableId = insertAccount(
                "la_flow_payable", "MERCHANT_PAYABLE:31:VND", "MERCHANT_PAYABLE",
                "MERCHANT", 31L, "VND", "ACTIVE"
        );
        long paymentTransactionId = insertTransaction(
                "ltxn_flow_payment",
                "PAYMENT_SUCCEEDED",
                "PAYMENT_INTENT",
                "pi_flow_payment",
                "VND",
                "Payment succeeded"
        );
        insertEntry(paymentTransactionId, clearingId, 1, "DEBIT", 1_000_000L);
        insertEntry(paymentTransactionId, payableId, 2, "CREDIT", 1_000_000L);

        long refundTransactionId = insertTransaction(
                "ltxn_flow_refund",
                "REFUND_SUCCEEDED",
                "REFUND",
                "re_flow_refund",
                "VND",
                "Refund succeeded"
        );
        insertEntry(refundTransactionId, payableId, 1, "DEBIT", 300_000L);
        insertEntry(refundTransactionId, clearingId, 2, "CREDIT", 300_000L);

        assertThat(queryStrings("""
                SELECT posting_type || ':' || reference_type || ':' || reference_id
                FROM ledger_transactions
                ORDER BY id
                """)).containsExactly(
                "PAYMENT_SUCCEEDED:PAYMENT_INTENT:pi_flow_payment",
                "REFUND_SUCCEEDED:REFUND:re_flow_refund"
        );
        assertThat(queryStrings("""
                SELECT ledger_tx.public_id || ':' || ledger_entry.entry_no || ':'
                    || ledger_entry.direction || ':' || account.account_code || ':'
                    || ledger_entry.amount_minor
                FROM ledger_entries ledger_entry
                JOIN ledger_transactions ledger_tx
                  ON ledger_tx.id = ledger_entry.ledger_transaction_id
                JOIN ledger_accounts account
                  ON account.id = ledger_entry.ledger_account_id
                ORDER BY ledger_tx.id, ledger_entry.entry_no
                """)).containsExactly(
                "ltxn_flow_payment:1:DEBIT:SYSTEM_CLEARING:VND:1000000",
                "ltxn_flow_payment:2:CREDIT:MERCHANT_PAYABLE:31:VND:1000000",
                "ltxn_flow_refund:1:DEBIT:MERCHANT_PAYABLE:31:VND:300000",
                "ltxn_flow_refund:2:CREDIT:SYSTEM_CLEARING:VND:300000"
        );
    }

    private static void assertColumns(String table, List<String> expected) throws SQLException {
        assertThat(queryStrings("""
                SELECT column_name || ':' || data_type || ':'
                    || COALESCE(character_maximum_length::text, '-') || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                ORDER BY ordinal_position
                """, table)).containsExactlyElementsOf(expected);
    }

    private static void assertConstraints(String table, List<String> expected)
            throws SQLException {
        assertThat(queryStrings("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_name NOT LIKE '%not_null'
                ORDER BY constraint_name
                """, table)).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static long insertAccount(
            String publicId,
            String accountCode,
            String accountType,
            String ownerType,
            Long ownerId,
            String currency,
            String status
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO ledger_accounts (
                    public_id, account_code, account_type, owner_type, owner_id,
                    currency, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                publicId,
                accountCode,
                accountType,
                ownerType,
                ownerId,
                currency,
                status);
    }

    private static long insertTransaction(
            String publicId,
            String postingType,
            String referenceType,
            String referenceId,
            String currency,
            String description
    ) throws SQLException {
        return insertAndReturnId("""
                INSERT INTO ledger_transactions (
                    public_id, posting_type, reference_type, reference_id,
                    currency, description, occurred_at, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                publicId,
                postingType,
                referenceType,
                referenceId,
                currency,
                description);
    }

    private static void insertEntry(
            long transactionId,
            long accountId,
            int entryNo,
            String direction,
            long amountMinor
    ) throws SQLException {
        execute("""
                INSERT INTO ledger_entries (
                    ledger_transaction_id, ledger_account_id, entry_no,
                    direction, amount_minor, created_at
                )
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, transactionId, accountId, entryNo, direction, amountMinor);
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

    @FunctionalInterface
    private interface SqlOperation {
        void execute() throws SQLException;
    }
}
