package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LedgerDuplicatePostingConcurrencyIntegrationTest extends PostgresIntegrationTest {

    private static final int DUPLICATE_CALLERS = 8;
    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired
    private LedgerPostingApi postingApi;

    @Autowired
    private LedgerReversalService reversalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanLedgerData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void samePaymentRaceShouldResolveOneCanonicalPostingForEveryCaller() throws Exception {
        List<LedgerPostingResult> results = runConcurrently(repeat(
                DUPLICATE_CALLERS,
                () -> postPayment("pi_concurrent_duplicate", 15L, 1_000_000L, VND)
        ));

        assertEquivalentRace(results);
        assertThat(countPosting("PAYMENT_SUCCEEDED", "pi_concurrent_duplicate"))
                .isEqualTo(1L);
        assertLedgerIntegrity(1L);
    }

    @Test
    void sameRefundRaceShouldResolveOneCanonicalPostingForEveryCaller() throws Exception {
        List<LedgerPostingResult> results = runConcurrently(repeat(
                DUPLICATE_CALLERS,
                () -> postRefund("re_concurrent_duplicate", 15L, 300_000L, VND)
        ));

        assertEquivalentRace(results);
        assertThat(countPosting("REFUND_SUCCEEDED", "re_concurrent_duplicate"))
                .isEqualTo(1L);
        assertLedgerIntegrity(1L);
    }

    @Test
    void sameReversalRaceShouldResolveOneCanonicalReversalForEveryCaller() throws Exception {
        LedgerPostingResult original = postPayment(
                "pi_concurrent_reversal",
                15L,
                900_000L,
                VND
        );
        ReverseLedgerTransactionCommand command = new ReverseLedgerTransactionCommand(
                original.ledgerTransactionPublicId(),
                "Concurrent reversal",
                OCCURRED_AT.plusSeconds(1)
        );

        List<LedgerPostingResult> results = runConcurrently(repeat(
                DUPLICATE_CALLERS,
                () -> reversalService.reverse(command)
        ));

        assertEquivalentRace(results);
        assertThat(countPosting("REVERSAL", original.ledgerTransactionPublicId()))
                .isEqualTo(1L);
        assertLedgerIntegrity(2L);
    }

    @Test
    void contradictoryPaymentRaceShouldFailClosedAroundOneCanonicalPosting()
            throws Exception {
        List<PostingAttempt> attempts = runConcurrently(List.of(
                () -> capture(() -> postPayment(
                        "pi_concurrent_conflict",
                        29L,
                        1_000_000L,
                        VND
                )),
                () -> capture(() -> postPayment(
                        "pi_concurrent_conflict",
                        29L,
                        999_999L,
                        VND
                ))
        ));

        assertContradictoryRace(attempts, "PAYMENT_SUCCEEDED", "pi_concurrent_conflict");
        List<Long> canonicalAmounts = canonicalEntryAmounts(
                "PAYMENT_SUCCEEDED",
                "pi_concurrent_conflict"
        );
        assertThat(canonicalAmounts).singleElement().isIn(999_999L, 1_000_000L);
        assertLedgerIntegrity(1L);
    }

    @Test
    void contradictoryRefundRaceShouldFailClosedAroundOneCanonicalPosting()
            throws Exception {
        List<PostingAttempt> attempts = runConcurrently(List.of(
                () -> capture(() -> postRefund(
                        "re_concurrent_conflict",
                        29L,
                        300_000L,
                        VND
                )),
                () -> capture(() -> postRefund(
                        "re_concurrent_conflict",
                        29L,
                        299_999L,
                        VND
                ))
        ));

        assertContradictoryRace(attempts, "REFUND_SUCCEEDED", "re_concurrent_conflict");
        List<Long> canonicalAmounts = canonicalEntryAmounts(
                "REFUND_SUCCEEDED",
                "re_concurrent_conflict"
        );
        assertThat(canonicalAmounts).singleElement().isIn(299_999L, 300_000L);
        assertLedgerIntegrity(1L);
    }

    @Test
    void unrelatedPaymentAndRefundPostingsShouldCompleteWithoutInterference()
            throws Exception {
        List<Supplier<LedgerPostingResult>> operations = IntStream.range(0, 8)
                .mapToObj(index -> unrelatedPosting(index))
                .toList();

        List<LedgerPostingResult> results = runConcurrently(operations);

        assertThat(results).extracting(LedgerPostingResult::outcome)
                .containsOnly(LedgerPostingOutcome.CREATED);
        assertThat(new HashSet<>(results.stream()
                .map(LedgerPostingResult::ledgerTransactionPublicId)
                .toList())).hasSize(8);
        assertThat(countRows("ledger_accounts")).isEqualTo(10L);
        assertLedgerIntegrity(8L);
    }

    @Test
    void firstUsePaymentRefundRaceShouldNotDeadlockOnSharedAccounts() throws Exception {
        long merchantId = 115L;
        List<LedgerPostingResult> results = runConcurrently(List.of(
                () -> postPayment("pi_cross_type_first_use", merchantId, 750_000L, VND),
                () -> postRefund("re_cross_type_first_use", merchantId, 250_000L, VND)
        ));

        assertThat(results).extracting(LedgerPostingResult::outcome)
                .containsOnly(LedgerPostingOutcome.CREATED);
        assertThat(countByAccountCode("SYSTEM_CLEARING:VND")).isEqualTo(1L);
        assertThat(countByAccountCode("MERCHANT_PAYABLE:" + merchantId + ":VND"))
                .isEqualTo(1L);
        assertThat(countRows("ledger_accounts")).isEqualTo(2L);
        assertLedgerIntegrity(2L);
    }

    private Supplier<LedgerPostingResult> unrelatedPosting(int index) {
        long merchantId = 200L + index;
        Currency currency = index % 2 == 0 ? VND : USD;
        long amountMinor = 10_000L + index;
        if (index % 2 == 0) {
            return () -> postPayment(
                    "pi_unrelated_" + index,
                    merchantId,
                    amountMinor,
                    currency
            );
        }
        return () -> postRefund(
                "re_unrelated_" + index,
                merchantId,
                amountMinor,
                currency
        );
    }

    private LedgerPostingResult postPayment(
            String paymentPublicId,
            long merchantId,
            long amountMinor,
            Currency currency
    ) {
        return inTransaction(() -> postingApi.postPaymentSucceeded(
                new PostPaymentSucceededCommand(
                        merchantId,
                        paymentPublicId,
                        new Money(amountMinor, currency),
                        OCCURRED_AT
                )
        ));
    }

    private LedgerPostingResult postRefund(
            String refundPublicId,
            long merchantId,
            long amountMinor,
            Currency currency
    ) {
        return inTransaction(() -> postingApi.postRefundSucceeded(
                new PostRefundSucceededCommand(
                        merchantId,
                        refundPublicId,
                        new Money(amountMinor, currency),
                        OCCURRED_AT
                )
        ));
    }

    private <T> T inTransaction(Supplier<T> operation) {
        T result = new TransactionTemplate(transactionManager).execute(
                status -> operation.get()
        );
        return java.util.Objects.requireNonNull(
                result,
                "transaction result must not be null"
        );
    }

    private PostingAttempt capture(Supplier<LedgerPostingResult> operation) {
        try {
            return new PostingAttempt(operation.get(), null);
        } catch (RuntimeException exception) {
            return new PostingAttempt(null, exception);
        }
    }

    private <T> List<T> runConcurrently(List<Supplier<T>> operations) throws Exception {
        int callerCount = operations.size();
        CountDownLatch ready = new CountDownLatch(callerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callerCount);
        List<Future<T>> futures = new ArrayList<>(callerCount);

        try {
            for (Supplier<T> operation : operations) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent Ledger race did not start");
                    }
                    return operation.get();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>(callerCount);
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static <T> List<Supplier<T>> repeat(int count, Supplier<T> operation) {
        return IntStream.range(0, count)
                .mapToObj(index -> operation)
                .toList();
    }

    private void assertEquivalentRace(List<LedgerPostingResult> results) {
        assertThat(results).hasSize(DUPLICATE_CALLERS);
        assertThat(results.stream()
                .filter(result -> result.outcome() == LedgerPostingOutcome.CREATED))
                .hasSize(1);
        assertThat(results.stream()
                .filter(result -> result.outcome() == LedgerPostingOutcome.ALREADY_POSTED))
                .hasSize(DUPLICATE_CALLERS - 1);
        assertThat(new HashSet<>(results.stream()
                .map(LedgerPostingResult::ledgerTransactionPublicId)
                .toList())).hasSize(1);
    }

    private void assertContradictoryRace(
            List<PostingAttempt> attempts,
            String postingType,
            String referenceId
    ) {
        assertThat(attempts).hasSize(2);
        assertThat(attempts.stream().filter(PostingAttempt::succeeded)).hasSize(1)
                .allSatisfy(attempt -> assertThat(attempt.result().outcome())
                        .isEqualTo(LedgerPostingOutcome.CREATED));
        assertThat(attempts.stream().filter(attempt -> !attempt.succeeded())).hasSize(1)
                .allSatisfy(attempt -> assertThat(attempt.failure())
                        .isInstanceOf(LedgerPostingConflictException.class)
                        .hasMessage(
                                "Existing ledger posting conflicts with supplied financial facts"
                        ));
        assertThat(countPosting(postingType, referenceId)).isEqualTo(1L);
    }

    private void assertLedgerIntegrity(long expectedTransactionCount) {
        assertThat(countRows("ledger_transactions")).isEqualTo(expectedTransactionCount);
        assertThat(countRows("ledger_entries")).isEqualTo(expectedTransactionCount * 2L);
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.public_id
                FROM ledger_transactions t
                LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id, t.public_id
                HAVING COUNT(e.id) <> 2
                """, String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT e.id
                FROM ledger_entries e
                LEFT JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.id IS NULL
                """, Long.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.public_id
                FROM ledger_transactions t
                JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id, t.public_id
                HAVING SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE 0 END)
                    <> SUM(CASE WHEN e.direction = 'CREDIT' THEN e.amount_minor ELSE 0 END)
                """, String.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
                SELECT t.public_id
                FROM ledger_transactions t
                JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id, t.public_id, e.entry_no
                HAVING COUNT(*) > 1
                """, String.class)).isEmpty();
    }

    private long countPosting(String postingType, String referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE posting_type = ? AND reference_id = ?
                """, Long.class, postingType, referenceId);
    }

    private List<Long> canonicalEntryAmounts(String postingType, String referenceId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT e.amount_minor
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.posting_type = ? AND t.reference_id = ?
                """, Long.class, postingType, referenceId);
    }

    private long countByAccountCode(String accountCode) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_accounts WHERE account_code = ?",
                Long.class,
                accountCode
        );
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record PostingAttempt(
            LedgerPostingResult result,
            RuntimeException failure
    ) {

        private boolean succeeded() {
            return result != null;
        }
    }
}
