package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerEntryDirection;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
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
class LedgerReversalIntegrationTest extends PostgresIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant ORIGINAL_OCCURRED_AT =
            Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant REVERSAL_OCCURRED_AT =
            Instant.parse("2026-09-01T09:00:00Z");

    @Autowired
    private LedgerReversalService reversalService;

    @Autowired
    private LedgerPostingApi postingApi;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanData() {
        dropReversalFailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts,
                    refunds, idempotency_records, payment_transactions, payment_intents,
                    refresh_tokens, merchant_api_keys, merchant_members, merchants, users
                RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void cleanTrigger() {
        dropReversalFailureTrigger();
    }

    @Test
    void shouldReversePaymentPostingWithoutChangingOriginalHistory() {
        LedgerPostingResult originalResult = postPayment(
                "pi_reversal_payment",
                15L,
                1_000_000L,
                VND
        );
        PostingSnapshot before = snapshot(findByPublicId(
                originalResult.ledgerTransactionPublicId()
        ));

        LedgerPostingResult result = reversalService.reverse(command(
                originalResult.ledgerTransactionPublicId(),
                "Correct duplicate payment posting"
        ));

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        LedgerTransaction reversal = findByPublicId(result.ledgerTransactionPublicId());
        assertFullReversal(before, reversal, "Correct duplicate payment posting");
        assertThat(snapshot(findByPublicId(originalResult.ledgerTransactionPublicId())))
                .isEqualTo(before);
        assertThat(countRows("ledger_transactions")).isEqualTo(2L);
        assertThat(countRows("ledger_entries")).isEqualTo(4L);
    }

    @Test
    void shouldReverseRefundPostingWithExactOriginalAccountsAmountsAndCurrency() {
        LedgerPostingResult originalResult = postRefund(
                "re_reversal_refund",
                29L,
                300_000L,
                USD
        );
        PostingSnapshot before = snapshot(findByPublicId(
                originalResult.ledgerTransactionPublicId()
        ));

        LedgerPostingResult result = reversalService.reverse(command(
                originalResult.ledgerTransactionPublicId(),
                "Correct duplicate refund posting"
        ));

        LedgerTransaction reversal = findByPublicId(result.ledgerTransactionPublicId());
        assertFullReversal(before, reversal, "Correct duplicate refund posting");
        assertThat(reversal.currency()).isEqualTo(USD);
        assertThat(snapshot(findByPublicId(originalResult.ledgerTransactionPublicId())))
                .isEqualTo(before);
    }

    @Test
    void equivalentDuplicateShouldReturnCanonicalReversalWithoutExtraRows() {
        LedgerPostingResult original = postPayment(
                "pi_reversal_duplicate",
                15L,
                1_000L,
                VND
        );
        ReverseLedgerTransactionCommand command = command(
                original.ledgerTransactionPublicId(),
                "Duplicate correction"
        );

        LedgerPostingResult first = reversalService.reverse(command);
        LedgerPostingResult replay = reversalService.reverse(command);

        assertThat(first.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(replay.outcome()).isEqualTo(LedgerPostingOutcome.ALREADY_POSTED);
        assertThat(replay.ledgerTransactionPublicId()).isEqualTo(
                first.ledgerTransactionPublicId()
        );
        assertThat(countReversals(original.ledgerTransactionPublicId())).isEqualTo(1L);
        assertThat(countRows("ledger_transactions")).isEqualTo(2L);
        assertThat(countRows("ledger_entries")).isEqualTo(4L);
    }

    @Test
    void concurrentDuplicateShouldCreateExactlyOneReversal() throws Exception {
        LedgerPostingResult original = postRefund(
                "re_reversal_concurrent",
                15L,
                400L,
                VND
        );
        ReverseLedgerTransactionCommand command = command(
                original.ledgerTransactionPublicId(),
                "Concurrent correction"
        );
        CountDownLatch start = new CountDownLatch(1);

        List<Future<LedgerPostingResult>> attempts;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            attempts = List.of(
                    executor.submit(() -> reverseAfter(start, command)),
                    executor.submit(() -> reverseAfter(start, command))
            );
            start.countDown();
            List<LedgerPostingResult> results = attempts.stream()
                    .map(LedgerReversalIntegrationTest::await)
                    .toList();
            assertThat(results).extracting(LedgerPostingResult::outcome)
                    .containsExactlyInAnyOrder(
                            LedgerPostingOutcome.CREATED,
                            LedgerPostingOutcome.ALREADY_POSTED
                    );
            assertThat(results).extracting(
                    LedgerPostingResult::ledgerTransactionPublicId
            ).hasSize(2).containsOnly(results.getFirst().ledgerTransactionPublicId());
        }

        assertThat(countReversals(original.ledgerTransactionPublicId())).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(4L);
    }

    @Test
    void shouldRejectUnknownOriginalAndReverseAReversal() {
        assertThatThrownBy(() -> reversalService.reverse(command(
                "ltxn_missing_original",
                "Missing correction"
        ))).isInstanceOf(LedgerTransactionNotFoundException.class)
                .hasMessage("The original ledger transaction was not found");

        LedgerPostingResult original = postPayment(
                "pi_reversal_chain",
                15L,
                1_000L,
                VND
        );
        LedgerPostingResult reversal = reversalService.reverse(command(
                original.ledgerTransactionPublicId(),
                "First reversal"
        ));

        assertThatThrownBy(() -> reversalService.reverse(
                new ReverseLedgerTransactionCommand(
                        reversal.ledgerTransactionPublicId(),
                        "Forbidden reversal chain",
                        REVERSAL_OCCURRED_AT.plusSeconds(1)
                )
        )).isInstanceOf(LedgerTransactionNotReversibleException.class)
                .hasMessage("A reversal ledger transaction cannot be reversed");
        assertThat(countRows("ledger_transactions")).isEqualTo(2L);
    }

    @Test
    void corruptOriginalShouldBeRejectedWithoutCreatingReversal() {
        LedgerPostingResult original = postPayment(
                "pi_reversal_corrupt",
                15L,
                1_000L,
                VND
        );
        jdbcTemplate.update("""
                UPDATE ledger_entries
                SET amount_minor = 999
                WHERE ledger_transaction_id = (
                    SELECT id FROM ledger_transactions WHERE public_id = ?
                )
                  AND entry_no = 2
                """, original.ledgerTransactionPublicId());

        assertThatThrownBy(() -> reversalService.reverse(command(
                original.ledgerTransactionPublicId(),
                "Unsafe correction"
        ))).isInstanceOf(LedgerReversalException.class)
                .hasMessage("The original ledger transaction cannot be safely reversed");
        assertThat(countReversals(original.ledgerTransactionPublicId())).isZero();
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
    }

    @Test
    void persistenceFailureShouldLeaveOriginalUnchangedWithoutPartialReversal() {
        LedgerPostingResult original = postRefund(
                "re_reversal_failure",
                15L,
                250L,
                VND
        );
        PostingSnapshot before = snapshot(findByPublicId(
                original.ledgerTransactionPublicId()
        ));
        createReversalFailureTrigger();

        assertThatThrownBy(() -> reversalService.reverse(command(
                original.ledgerTransactionPublicId(),
                "Failed correction"
        ))).isInstanceOf(LedgerPostingException.class)
                .hasMessage("Ledger posting could not be completed safely");

        assertThat(snapshot(findByPublicId(original.ledgerTransactionPublicId())))
                .isEqualTo(before);
        assertThat(countReversals(original.ledgerTransactionPublicId())).isZero();
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
    }

    @Test
    void reversalShouldNotMutatePaymentOrRefundState() {
        SourceFixture source = insertSucceededRefundSource();
        LedgerPostingResult original = postRefund(
                source.refundPublicId(),
                source.merchantId(),
                300L,
                VND
        );
        Map<String, Object> paymentBefore = paymentState(source.paymentInternalId());
        Map<String, Object> refundBefore = refundState(source.refundPublicId());

        reversalService.reverse(command(
                original.ledgerTransactionPublicId(),
                "Accounting-only correction"
        ));

        assertThat(paymentState(source.paymentInternalId())).isEqualTo(paymentBefore);
        assertThat(refundState(source.refundPublicId())).isEqualTo(refundBefore);
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
                        ORIGINAL_OCCURRED_AT
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
                        ORIGINAL_OCCURRED_AT
                )
        ));
    }

    private LedgerPostingResult reverseAfter(
            CountDownLatch start,
            ReverseLedgerTransactionCommand command
    ) {
        await(start);
        return reversalService.reverse(command);
    }

    private LedgerTransaction findByPublicId(String publicId) {
        return inTransaction(() -> transactionRepository.findByPublicId(publicId)
                .orElseThrow());
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

    private static ReverseLedgerTransactionCommand command(
            String originalPublicId,
            String description
    ) {
        return new ReverseLedgerTransactionCommand(
                originalPublicId,
                description,
                REVERSAL_OCCURRED_AT
        );
    }

    private long countReversals(String originalPublicId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE posting_type = 'REVERSAL'
                  AND reference_type = 'LEDGER_TRANSACTION'
                  AND reference_id = ?
                """, Long.class, originalPublicId);
        return count == null ? 0L : count;
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Long.class
        );
        return count == null ? 0L : count;
    }

    private static PostingSnapshot snapshot(LedgerTransaction transaction) {
        return new PostingSnapshot(
                transaction.publicId(),
                transaction.postingType(),
                transaction.businessReference(),
                transaction.currency(),
                transaction.description(),
                transaction.occurredAt(),
                transaction.createdAt(),
                transaction.entries().stream().map(EntrySnapshot::from).toList()
        );
    }

    private static void assertFullReversal(
            PostingSnapshot original,
            LedgerTransaction reversal,
            String expectedDescription
    ) {
        assertThat(reversal.postingType()).isEqualTo(LedgerPostingType.REVERSAL);
        assertThat(reversal.businessReference()).isEqualTo(
                LedgerBusinessReference.ledgerTransaction(original.publicId())
        );
        assertThat(reversal.currency()).isEqualTo(original.currency());
        assertThat(reversal.description()).isEqualTo(expectedDescription);
        assertThat(reversal.occurredAt()).isEqualTo(REVERSAL_OCCURRED_AT);
        assertThat(reversal.entries()).hasSameSizeAs(original.entries());
        for (int index = 0; index < original.entries().size(); index++) {
            EntrySnapshot source = original.entries().get(index);
            LedgerEntry reversed = reversal.entries().get(index);
            assertThat(reversed.entryNo()).isEqualTo(source.entryNo());
            assertThat(reversed.ledgerAccountId()).isEqualTo(source.accountId());
            assertThat(reversed.direction()).isEqualTo(source.direction().opposite());
            assertThat(reversed.amountMinor()).isEqualTo(source.amountMinor());
            assertThat(reversed.accountCurrency()).isEqualTo(original.currency());
        }
        long debits = reversal.entries().stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.DEBIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();
        long credits = reversal.entries().stream()
                .filter(entry -> entry.direction() == LedgerEntryDirection.CREDIT)
                .mapToLong(LedgerEntry::amountMinor)
                .sum();
        assertThat(debits).isPositive().isEqualTo(credits);
    }

    private SourceFixture insertSucceededRefundSource() {
        Long merchantId = jdbcTemplate.queryForObject("""
                INSERT INTO merchants (
                    public_id, name, status, created_at, updated_at, version
                )
                VALUES ('mrc_reversal_source', 'Reversal Source', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """, Long.class, utc(ORIGINAL_OCCURRED_AT), utc(ORIGINAL_OCCURRED_AT));
        Long paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, amount_minor, currency, status,
                    refunded_amount_minor, refund_reserved_minor,
                    created_at, updated_at, version
                )
                VALUES ('pi_reversal_source', ?, 1000, 'VND', 'PARTIALLY_REFUNDED',
                    300, 0, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                merchantId,
                utc(ORIGINAL_OCCURRED_AT),
                utc(ORIGINAL_OCCURRED_AT.plusSeconds(1))
        );
        jdbcTemplate.update("""
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor, currency,
                    status, reason, provider, provider_refund_id,
                    created_at, updated_at, completed_at, version
                )
                VALUES ('re_reversal_source', ?, ?, 300, 'VND', 'SUCCEEDED',
                    'CUSTOMER_REQUEST', 'SIMULATOR', 'provider_reversal_source',
                    ?, ?, ?, 0)
                """,
                merchantId,
                paymentId,
                utc(ORIGINAL_OCCURRED_AT),
                utc(ORIGINAL_OCCURRED_AT.plusSeconds(1)),
                utc(ORIGINAL_OCCURRED_AT.plusSeconds(1))
        );
        return new SourceFixture(
                java.util.Objects.requireNonNull(merchantId),
                java.util.Objects.requireNonNull(paymentId),
                "re_reversal_source"
        );
    }

    private Map<String, Object> paymentState(long internalId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, refunded_amount_minor, refund_reserved_minor,
                    updated_at, version
                FROM payment_intents
                WHERE id = ?
                """, internalId);
    }

    private Map<String, Object> refundState(String publicId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, provider_refund_id, failure_code, failure_message,
                    updated_at, completed_at, version
                FROM refunds
                WHERE public_id = ?
                """, publicId);
    }

    private void createReversalFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_reversal_insert() RETURNS trigger AS $$
                BEGIN
                    IF NEW.posting_type = 'REVERSAL' THEN
                        RAISE EXCEPTION 'forced reversal insert failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_reversal_insert_trigger
                BEFORE INSERT ON ledger_transactions
                FOR EACH ROW EXECUTE FUNCTION fail_reversal_insert()
                """);
    }

    private void dropReversalFailureTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS fail_reversal_insert_trigger ON ledger_transactions"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_reversal_insert()");
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static LedgerPostingResult await(Future<LedgerPostingResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent reversal did not complete", exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to reverse posting");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to reverse", exception);
        }
    }

    private record PostingSnapshot(
            String publicId,
            LedgerPostingType postingType,
            LedgerBusinessReference reference,
            Currency currency,
            String description,
            Instant occurredAt,
            Instant createdAt,
            List<EntrySnapshot> entries
    ) {
    }

    private record EntrySnapshot(
            int entryNo,
            long accountId,
            LedgerEntryDirection direction,
            long amountMinor,
            Instant createdAt
    ) {

        private static EntrySnapshot from(LedgerEntry entry) {
            return new EntrySnapshot(
                    entry.entryNo(),
                    entry.ledgerAccountId(),
                    entry.direction(),
                    entry.amountMinor(),
                    entry.createdAt()
            );
        }
    }

    private record SourceFixture(
            long merchantId,
            long paymentInternalId,
            String refundPublicId
    ) {
    }
}
