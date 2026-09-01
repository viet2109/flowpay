package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LedgerAccountProvisioningIntegrationTest extends PostgresIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private LedgerAccountProvisioningService provisioningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanLedgerData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldCreateAndReuseCanonicalSystemAndMerchantAccounts() {
        LedgerAccount firstSystem = provisioningService.requireSystemClearing(VND);
        LedgerAccount repeatedSystem = provisioningService.requireSystemClearing(VND);
        LedgerAccount firstMerchant = provisioningService.requireMerchantPayable(15L, VND);
        LedgerAccount repeatedMerchant = provisioningService.requireMerchantPayable(15L, VND);

        assertCanonicalPair(firstSystem, repeatedSystem, "SYSTEM_CLEARING:VND");
        assertCanonicalPair(firstMerchant, repeatedMerchant, "MERCHANT_PAYABLE:15:VND");
        assertThat(firstSystem.publicId()).startsWith("la_");
        assertThat(firstMerchant.publicId()).startsWith("la_");
        assertThat(firstSystem.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(firstMerchant.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(countAccounts()).isEqualTo(2L);
    }

    @Test
    void shouldProvisionSeparateAccountsForDifferentCurrenciesAndMerchants() {
        LedgerAccount merchantVnd = provisioningService.requireMerchantPayable(15L, VND);
        LedgerAccount merchantUsd = provisioningService.requireMerchantPayable(15L, USD);
        LedgerAccount otherMerchantVnd = provisioningService.requireMerchantPayable(16L, VND);

        assertThat(Set.of(
                merchantVnd.internalId(),
                merchantUsd.internalId(),
                otherMerchantVnd.internalId()
        )).hasSize(3);
        assertThat(List.of(
                merchantVnd.accountCode().value(),
                merchantUsd.accountCode().value(),
                otherMerchantVnd.accountCode().value()
        )).containsExactly(
                "MERCHANT_PAYABLE:15:VND",
                "MERCHANT_PAYABLE:15:USD",
                "MERCHANT_PAYABLE:16:VND"
        );
        assertThat(countAccounts()).isEqualTo(3L);
    }

    @Test
    void concurrentSystemProvisioningShouldCreateOneCanonicalRow() throws Exception {
        List<LedgerAccount> results = provisionConcurrently(
                12,
                () -> provisioningService.requireSystemClearing(VND)
        );

        assertOneCanonicalAccount(results);
        assertThat(countByCode("SYSTEM_CLEARING:VND")).isEqualTo(1L);
    }

    @Test
    void concurrentMerchantProvisioningShouldCreateOneCanonicalRowForAllCallers()
            throws Exception {
        List<LedgerAccount> results = provisionConcurrently(
                12,
                () -> provisioningService.requireMerchantPayable(29L, VND)
        );

        assertOneCanonicalAccount(results);
        assertThat(countByCode("MERCHANT_PAYABLE:29:VND")).isEqualTo(1L);
    }

    @Test
    void shouldFailClosedForConflictingPersistedMetadataWithoutLeakingInternals() {
        insertRawAccount(
                "la_conflicting_metadata",
                "SYSTEM_CLEARING:VND",
                "MERCHANT_PAYABLE",
                "MERCHANT",
                15L,
                "VND",
                "ACTIVE"
        );

        assertThatThrownBy(() -> provisioningService.requireSystemClearing(VND))
                .isInstanceOf(LedgerAccountProvisioningException.class)
                .hasMessage("Ledger account could not be provisioned safely")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("uq_ledger_accounts_account_code")
                        .doesNotContain("IllegalArgumentException")
                        .doesNotContain("Hibernate"));
        assertThat(countByCode("SYSTEM_CLEARING:VND")).isEqualTo(1L);
    }

    @Test
    void shouldRejectInactiveCanonicalAccount() {
        insertRawAccount(
                "la_closed_system",
                "SYSTEM_CLEARING:VND",
                "SYSTEM_CLEARING",
                "SYSTEM",
                null,
                "VND",
                "CLOSED"
        );

        assertThatThrownBy(() -> provisioningService.requireSystemClearing(VND))
                .isInstanceOf(LedgerAccountProvisioningException.class)
                .hasMessage("Canonical ledger account metadata is inconsistent");
        assertThat(countByCode("SYSTEM_CLEARING:VND")).isEqualTo(1L);
    }

    private List<LedgerAccount> provisionConcurrently(
            int callerCount,
            Supplier<LedgerAccount> operation
    ) throws Exception {
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callerCount);
        List<Future<LedgerAccount>> futures = new ArrayList<>(callerCount);

        try {
            for (int index = 0; index < callerCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent provisioning did not start");
                    }
                    return operation.get();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<LedgerAccount> results = new ArrayList<>(callerCount);
            for (Future<LedgerAccount> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void insertRawAccount(
            String publicId,
            String accountCode,
            String accountType,
            String ownerType,
            Long ownerId,
            String currency,
            String status
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO ledger_accounts (
                    public_id, account_code, account_type, owner_type, owner_id,
                    currency, status, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                publicId,
                accountCode,
                accountType,
                ownerType,
                ownerId,
                currency,
                status,
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
    }

    private long countAccounts() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts",
                Long.class
        );
        return count == null ? 0L : count;
    }

    private long countByCode(String accountCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_code = ?",
                Long.class,
                accountCode
        );
        return count == null ? 0L : count;
    }

    private static void assertCanonicalPair(
            LedgerAccount first,
            LedgerAccount second,
            String expectedCode
    ) {
        assertThat(first.internalId()).isPositive().isEqualTo(second.internalId());
        assertThat(first.publicId()).isEqualTo(second.publicId());
        assertThat(first.accountCode().value()).isEqualTo(expectedCode);
        assertThat(second.accountCode().value()).isEqualTo(expectedCode);
    }

    private static void assertOneCanonicalAccount(List<LedgerAccount> accounts) {
        assertThat(accounts).isNotEmpty();
        assertThat(new HashSet<>(accounts.stream()
                .map(LedgerAccount::internalId)
                .toList())).hasSize(1);
        assertThat(new HashSet<>(accounts.stream()
                .map(LedgerAccount::publicId)
                .toList())).hasSize(1);
    }
}
