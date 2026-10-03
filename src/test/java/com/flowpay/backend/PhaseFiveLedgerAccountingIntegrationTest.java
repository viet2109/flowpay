package com.flowpay.backend;

import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelopeMapper;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxEvent;
import com.flowpay.backend.infrastructure.messaging.outbox.OutboxRepository;
import com.flowpay.backend.infrastructure.messaging.rabbit.LedgerIntegrationEventDispatcher;
import com.flowpay.backend.ledger.application.LedgerPostingException;
import com.flowpay.backend.ledger.application.LedgerPostingOutcome;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.application.LedgerReversalService;
import com.flowpay.backend.ledger.application.ReverseLedgerTransactionCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationService;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.application.PaymentRefundApi;
import com.flowpay.backend.payment.application.PaymentRefundReservation;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import com.flowpay.backend.refund.application.FinalizeRefundCommand;
import com.flowpay.backend.refund.application.FinalizeRefundService;
import com.flowpay.backend.refund.application.RefundProviderResult;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PhaseFiveLedgerAccountingIntegrationTest extends PostgresIntegrationTest {

    private static final Instant FIXTURE_TIME = Instant.parse("2026-09-01T09:00:00Z");

    @Autowired
    private FinalizePaymentConfirmationService paymentFinalization;

    @Autowired
    private PaymentRefundApi paymentRefundApi;

    @Autowired
    private FinalizeRefundService refundFinalization;

    @Autowired
    private LedgerReversalService reversalService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private IntegrationEventEnvelopeMapper envelopeMapper;

    @Autowired
    private LedgerIntegrationEventDispatcher eventDispatcher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        dropEntryFailureTrigger();
        jdbcTemplate.update("""
                TRUNCATE TABLE outbox_events, ledger_entries, ledger_transactions,
                    ledger_accounts, refunds, idempotency_records, payment_transactions,
                    payment_intents, merchant_members, merchant_api_keys, refresh_tokens,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void cleanFailureTrigger() {
        dropEntryFailureTrigger();
    }

    @Test
    void shouldPostExactPaymentAccountingOnCleanV001ThroughV011Schema() {
        assertThat(jdbcTemplate.queryForList("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = true
                ORDER BY installed_rank
                """, String.class)).containsExactly(
                "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011"
        );

        long merchantId = insertMerchant("mrc_accounting_payment");
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "payment_exact",
                1_000_000L,
                "VND"
        );

        finalizePayment(payment, ProviderOutcome.SUCCESS);

        assertPaymentState(payment, "SUCCEEDED", 0L, 0L);
        assertThat(paymentTransactionStatus(payment)).isEqualTo("SUCCEEDED");
        assertPosting(
                "PAYMENT_SUCCEEDED",
                payment.publicId(),
                "PAYMENT_INTENT",
                "VND",
                List.of(
                        new ExpectedEntry(1, "SYSTEM_CLEARING:VND", "DEBIT", 1_000_000L),
                        new ExpectedEntry(
                                2,
                                "MERCHANT_PAYABLE:" + merchantId + ":VND",
                                "CREDIT",
                                1_000_000L
                        )
                )
        );
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
        assertEveryPostingBalanced();
    }

    @Test
    void shouldPreservePaymentAndPartialThenFullRefundHistory() {
        long merchantId = insertMerchant("mrc_accounting_refunds");
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "refund_sequence",
                1_000_000L,
                "VND"
        );
        finalizePayment(payment, ProviderOutcome.SUCCESS);

        RefundFixture partial = prepareRefund(payment, "partial_300", 300_000L);
        finalizeRefund(partial, RefundProviderOutcome.SUCCESS);
        assertPaymentState(payment, "PARTIALLY_REFUNDED", 300_000L, 0L);

        RefundFixture remainder = prepareRefund(payment, "remainder_700", 700_000L);
        finalizeRefund(remainder, RefundProviderOutcome.SUCCESS);

        assertPaymentState(payment, "REFUNDED", 1_000_000L, 0L);
        assertRefundState(partial, "SUCCEEDED", "provider_partial_300");
        assertRefundState(remainder, "SUCCEEDED", "provider_remainder_700");
        assertPosting(
                "REFUND_SUCCEEDED",
                partial.publicId(),
                "REFUND",
                "VND",
                refundEntries(merchantId, "VND", 300_000L)
        );
        assertPosting(
                "REFUND_SUCCEEDED",
                remainder.publicId(),
                "REFUND",
                "VND",
                refundEntries(merchantId, "VND", 700_000L)
        );
        assertThat(postingTypes()).containsExactly(
                "PAYMENT_SUCCEEDED",
                "REFUND_SUCCEEDED",
                "REFUND_SUCCEEDED"
        );
        assertThat(merchantPayableMovement(merchantId, "VND"))
                .isEqualTo(new AccountMovement(1_000_000L, 1_000_000L));
        assertEveryPostingBalanced();
    }

    @Test
    void shouldReuseCanonicalAccountsByMerchantAndCurrency() {
        long firstMerchant = insertMerchant("mrc_account_reuse_one");
        long secondMerchant = insertMerchant("mrc_account_reuse_two");

        finalizePayment(
                insertProcessingPayment(firstMerchant, "one_vnd_a", 100L, "VND"),
                ProviderOutcome.SUCCESS
        );
        finalizePayment(
                insertProcessingPayment(firstMerchant, "one_vnd_b", 200L, "VND"),
                ProviderOutcome.SUCCESS
        );
        finalizePayment(
                insertProcessingPayment(secondMerchant, "two_vnd", 300L, "VND"),
                ProviderOutcome.SUCCESS
        );
        finalizePayment(
                insertProcessingPayment(firstMerchant, "one_usd", 400L, "USD"),
                ProviderOutcome.SUCCESS
        );

        assertThat(countAccount("SYSTEM_CLEARING", null, "VND")).isEqualTo(1L);
        assertThat(countAccount("SYSTEM_CLEARING", null, "USD")).isEqualTo(1L);
        assertThat(countAccount("MERCHANT_PAYABLE", firstMerchant, "VND")).isEqualTo(1L);
        assertThat(countAccount("MERCHANT_PAYABLE", secondMerchant, "VND")).isEqualTo(1L);
        assertThat(countAccount("MERCHANT_PAYABLE", firstMerchant, "USD")).isEqualTo(1L);
        assertThat(countRows("ledger_accounts")).isEqualTo(5L);
        assertThat(countRows("ledger_transactions")).isEqualTo(4L);
        assertEveryPostingBalanced();
    }

    @ParameterizedTest
    @EnumSource(value = ProviderOutcome.class, names = {"DECLINED", "UNKNOWN"})
    void nonSuccessfulPaymentShouldNotCreateLedgerPosting(ProviderOutcome outcome) {
        long merchantId = insertMerchant("mrc_payment_" + outcome.name().toLowerCase());
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "payment_" + outcome.name().toLowerCase(),
                500_000L,
                "VND"
        );

        finalizePayment(payment, outcome);

        assertThat(paymentStatus(payment)).isEqualTo(
                outcome == ProviderOutcome.UNKNOWN ? "PROCESSING" : "FAILED"
        );
        assertThat(paymentTransactionStatus(payment)).isEqualTo(
                outcome == ProviderOutcome.UNKNOWN ? "UNKNOWN" : "FAILED"
        );
        assertNoLedgerRows();
    }

    @ParameterizedTest
    @EnumSource(value = RefundProviderOutcome.class, names = {"DECLINED", "UNKNOWN"})
    void nonSuccessfulRefundShouldNotCreateRefundPosting(RefundProviderOutcome outcome) {
        long merchantId = insertMerchant("mrc_refund_" + outcome.name().toLowerCase());
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "refund_" + outcome.name().toLowerCase(),
                1_000L,
                "USD"
        );
        finalizePayment(payment, ProviderOutcome.SUCCESS);
        RefundFixture refund = prepareRefund(
                payment,
                outcome.name().toLowerCase(),
                250L
        );

        finalizeRefund(refund, outcome);

        assertThat(countPosting("REFUND_SUCCEEDED", refund.publicId())).isZero();
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
        if (outcome == RefundProviderOutcome.UNKNOWN) {
            assertRefundState(refund, "PROCESSING", "provider_pending_unknown");
            assertPaymentState(payment, "SUCCEEDED", 0L, 250L);
        } else {
            assertRefundState(refund, "FAILED", null);
            assertPaymentState(payment, "SUCCEEDED", 0L, 0L);
        }
        assertEveryPostingBalanced();
    }

    @Test
    void paymentLedgerEntryFailureShouldNotRollbackCommittedSourceAndOutbox() {
        long merchantId = insertMerchant("mrc_payment_atomicity");
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "payment_atomicity",
                750_000L,
                "VND"
        );
        installSecondEntryFailureTrigger();

        finalizePaymentSourceOnly(payment, ProviderOutcome.SUCCESS);

        assertThatThrownBy(() -> dispatchOutbox(payment.publicId()))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessage("Ledger posting could not be completed safely");

        assertThat(paymentStatus(payment)).isEqualTo("SUCCEEDED");
        assertThat(paymentTransactionStatus(payment)).isEqualTo("SUCCEEDED");
        assertThat(countRows("outbox_events")).isEqualTo(1L);
        assertNoLedgerRows();
    }

    @Test
    void refundLedgerEntryFailureShouldNotRollbackCommittedSourceAndOutbox() {
        long merchantId = insertMerchant("mrc_refund_atomicity");
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "refund_atomicity",
                1_000L,
                "USD"
        );
        finalizePayment(payment, ProviderOutcome.SUCCESS);
        RefundFixture refund = prepareRefund(payment, "refund_atomicity", 325L);
        assertPaymentState(payment, "SUCCEEDED", 0L, 325L);
        installSecondEntryFailureTrigger();

        finalizeRefundSourceOnly(refund, RefundProviderOutcome.SUCCESS);

        assertThatThrownBy(() -> dispatchOutbox(refund.publicId()))
                .isInstanceOf(LedgerPostingException.class)
                .hasMessage("Ledger posting could not be completed safely");

        assertPaymentState(payment, "PARTIALLY_REFUNDED", 325L, 0L);
        assertRefundState(refund, "SUCCEEDED", "provider_refund_atomicity");
        assertThat(countPosting("REFUND_SUCCEEDED", refund.publicId())).isZero();
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
        assertThat(countRows("outbox_events")).isEqualTo(2L);
        assertEveryPostingBalanced();
    }

    @Test
    void reversalShouldExactlyOffsetOriginalPaymentEntriesWithoutMutation() {
        long merchantId = insertMerchant("mrc_accounting_reversal");
        PaymentFixture payment = insertProcessingPayment(
                merchantId,
                "payment_reversal",
                900_000L,
                "VND"
        );
        finalizePayment(payment, ProviderOutcome.SUCCESS);
        String originalPublicId = postingPublicId("PAYMENT_SUCCEEDED", payment.publicId());
        PostingSnapshot originalBefore = postingSnapshot(originalPublicId);

        LedgerPostingResult result = reversalService.reverse(
                new ReverseLedgerTransactionCommand(
                        originalPublicId,
                        "Reverse payment accounting",
                        FIXTURE_TIME.plusSeconds(60)
                )
        );

        PostingSnapshot originalAfter = postingSnapshot(originalPublicId);
        PostingSnapshot reversal = postingSnapshot(result.ledgerTransactionPublicId());
        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(originalAfter).isEqualTo(originalBefore);
        assertThat(reversal.postingType()).isEqualTo("REVERSAL");
        assertThat(reversal.referenceType()).isEqualTo("LEDGER_TRANSACTION");
        assertThat(reversal.referenceId()).isEqualTo(originalPublicId);
        assertThat(reversal.currency()).isEqualTo(originalBefore.currency());
        assertThat(reversal.entries()).containsExactly(
                originalBefore.entries().stream()
                        .map(EntrySnapshot::opposite)
                        .toArray(EntrySnapshot[]::new)
        );
        assertThat(netMovementForTransactions(
                originalPublicId,
                result.ledgerTransactionPublicId()
        )).allSatisfy(value -> assertThat(value).isZero());
        assertEveryPostingBalanced();
    }

    private PaymentFixture insertProcessingPayment(
            long merchantId,
            String suffix,
            long amountMinor,
            String currency
    ) {
        String paymentPublicId = "pi_accounting_" + suffix;
        Long paymentInternalId = jdbcTemplate.queryForObject("""
                INSERT INTO payment_intents (
                    public_id, merchant_id, merchant_order_id, description,
                    amount_minor, currency, status, refunded_amount_minor,
                    refund_reserved_minor, created_at, updated_at, version
                )
                VALUES (?, ?, ?, 'Ledger accounting integration', ?, ?, 'PROCESSING',
                    0, 0, ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                paymentPublicId,
                merchantId,
                "ORDER-" + suffix,
                amountMinor,
                currency,
                utc(FIXTURE_TIME),
                utc(FIXTURE_TIME.plusSeconds(1))
        );
        String transactionPublicId = "ptxn_accounting_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (
                    public_id, payment_intent_id, attempt_no, provider, status,
                    started_at, created_at, updated_at, version
                )
                VALUES (?, ?, 1, 'SIMULATOR', 'PROCESSING', ?, ?, ?, 0)
                """,
                transactionPublicId,
                paymentInternalId,
                utc(FIXTURE_TIME.plusSeconds(1)),
                utc(FIXTURE_TIME),
                utc(FIXTURE_TIME.plusSeconds(1))
        );
        return new PaymentFixture(
                merchantId,
                java.util.Objects.requireNonNull(paymentInternalId),
                paymentPublicId,
                transactionPublicId,
                amountMinor,
                currency
        );
    }

    private void finalizePayment(PaymentFixture payment, ProviderOutcome outcome) {
        finalizePaymentSourceOnly(payment, outcome);
        if (outcome == ProviderOutcome.SUCCESS) {
            dispatchOutbox(payment.publicId());
        }
    }

    private void finalizePaymentSourceOnly(PaymentFixture payment, ProviderOutcome outcome) {
        paymentFinalization.finalizeConfirmation(new FinalizePaymentConfirmationCommand(
                payment.publicId(),
                payment.transactionPublicId(),
                paymentResult(outcome, payment.publicId())
        ));
    }

    private void dispatchOutbox(String aggregateId) {
        OutboxEvent event = outboxRepository.findDueUnpublished(
                        Instant.now().plusSeconds(60),
                        100
                ).stream()
                .filter(candidate -> candidate.aggregateId().equals(aggregateId))
                .findFirst()
                .orElseThrow();
        eventDispatcher.dispatch(envelopeMapper.from(event));
    }

    private RefundFixture prepareRefund(
            PaymentFixture payment,
            String suffix,
            long amountMinor
    ) {
        PaymentRefundReservation reservation = paymentRefundApi.reserveRefund(
                payment.merchantId(),
                payment.publicId(),
                amountMinor
        );
        String refundPublicId = "re_accounting_" + suffix;
        jdbcTemplate.update("""
                INSERT INTO refunds (
                    public_id, merchant_id, payment_intent_id, amount_minor, currency,
                    status, reason, provider, created_at, updated_at, version
                )
                VALUES (?, ?, ?, ?, ?, 'PROCESSING', 'CUSTOMER_REQUEST',
                    'SIMULATOR', ?, ?, 0)
                """,
                refundPublicId,
                payment.merchantId(),
                reservation.paymentInternalId(),
                amountMinor,
                payment.currency(),
                utc(FIXTURE_TIME.plusSeconds(2)),
                utc(FIXTURE_TIME.plusSeconds(2))
        );
        return new RefundFixture(payment, refundPublicId, amountMinor);
    }

    private void finalizeRefund(RefundFixture refund, RefundProviderOutcome outcome) {
        finalizeRefundSourceOnly(refund, outcome);
        if (outcome == RefundProviderOutcome.SUCCESS) {
            dispatchOutbox(refund.publicId());
        }
    }

    private void finalizeRefundSourceOnly(
            RefundFixture refund,
            RefundProviderOutcome outcome
    ) {
        refundFinalization.finalizeRefund(new FinalizeRefundCommand(
                refund.payment().merchantId(),
                refund.publicId(),
                refund.payment().publicId(),
                refundResult(outcome, refund.publicId())
        ));
    }

    private long insertMerchant(String publicId) {
        Long merchantId = jdbcTemplate.queryForObject("""
                INSERT INTO merchants (
                    public_id, name, status, created_at, updated_at, version
                )
                VALUES (?, 'Ledger Accounting Store', 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                utc(FIXTURE_TIME),
                utc(FIXTURE_TIME)
        );
        return java.util.Objects.requireNonNull(merchantId);
    }

    private void assertPosting(
            String postingType,
            String referenceId,
            String referenceType,
            String currency,
            List<ExpectedEntry> expectedEntries
    ) {
        String publicId = postingPublicId(postingType, referenceId);
        PostingSnapshot posting = postingSnapshot(publicId);
        assertThat(posting.postingType()).isEqualTo(postingType);
        assertThat(posting.referenceType()).isEqualTo(referenceType);
        assertThat(posting.referenceId()).isEqualTo(referenceId);
        assertThat(posting.currency()).isEqualTo(currency);
        assertThat(posting.entries()).containsExactlyElementsOf(
                expectedEntries.stream()
                        .map(expected -> new EntrySnapshot(
                                expected.entryNo(),
                                expected.accountCode(),
                                expected.direction(),
                                expected.amountMinor()
                        ))
                        .toList()
        );
    }

    private PostingSnapshot postingSnapshot(String publicId) {
        Map<String, Object> header = jdbcTemplate.queryForMap("""
                SELECT posting_type, reference_type, reference_id, TRIM(currency) AS currency
                FROM ledger_transactions
                WHERE public_id = ?
                """, publicId);
        List<EntrySnapshot> entries = jdbcTemplate.query("""
                SELECT e.entry_no, a.account_code, e.direction, e.amount_minor
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                JOIN ledger_accounts a ON a.id = e.ledger_account_id
                WHERE t.public_id = ?
                ORDER BY e.entry_no
                """,
                (resultSet, rowNum) -> new EntrySnapshot(
                        resultSet.getInt("entry_no"),
                        resultSet.getString("account_code"),
                        resultSet.getString("direction"),
                        resultSet.getLong("amount_minor")
                ),
                publicId
        );
        return new PostingSnapshot(
                (String) header.get("posting_type"),
                (String) header.get("reference_type"),
                (String) header.get("reference_id"),
                (String) header.get("currency"),
                entries
        );
    }

    private String postingPublicId(String postingType, String referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT public_id
                FROM ledger_transactions
                WHERE posting_type = ? AND reference_id = ?
                """, String.class, postingType, referenceId);
    }

    private void assertEveryPostingBalanced() {
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
                LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                GROUP BY t.id, t.public_id
                HAVING COUNT(e.id) < 2
                """, String.class)).isEmpty();
    }

    private void assertPaymentState(
            PaymentFixture payment,
            String status,
            long refundedAmount,
            long reservedAmount
    ) {
        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT status, refunded_amount_minor, refund_reserved_minor
                FROM payment_intents
                WHERE id = ?
                """, payment.internalId());
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(((Number) state.get("refunded_amount_minor")).longValue())
                .isEqualTo(refundedAmount);
        assertThat(((Number) state.get("refund_reserved_minor")).longValue())
                .isEqualTo(reservedAmount);
    }

    private void assertRefundState(
            RefundFixture refund,
            String status,
            String providerRefundId
    ) {
        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT status, provider_refund_id
                FROM refunds
                WHERE public_id = ?
                """, refund.publicId());
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(state.get("provider_refund_id")).isEqualTo(providerRefundId);
    }

    private String paymentStatus(PaymentFixture payment) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?",
                String.class,
                payment.internalId()
        );
    }

    private String paymentTransactionStatus(PaymentFixture payment) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transactions WHERE public_id = ?",
                String.class,
                payment.transactionPublicId()
        );
    }

    private long countPosting(String postingType, String referenceId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_transactions
                WHERE posting_type = ? AND reference_id = ?
                """, Long.class, postingType, referenceId);
    }

    private long countAccount(String accountType, Long ownerId, String currency) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ledger_accounts
                WHERE account_type = ?
                  AND owner_id IS NOT DISTINCT FROM ?
                  AND TRIM(currency) = ?
                """, Long.class, accountType, ownerId, currency);
    }

    private AccountMovement merchantPayableMovement(long merchantId, String currency) {
        Map<String, Object> movement = jdbcTemplate.queryForMap("""
                SELECT
                    COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                        THEN e.amount_minor ELSE 0 END), 0) AS credits,
                    COALESCE(SUM(CASE WHEN e.direction = 'DEBIT'
                        THEN e.amount_minor ELSE 0 END), 0) AS debits
                FROM ledger_entries e
                JOIN ledger_accounts a ON a.id = e.ledger_account_id
                WHERE a.account_type = 'MERCHANT_PAYABLE'
                  AND a.owner_id = ?
                  AND TRIM(a.currency) = ?
                """, merchantId, currency);
        return new AccountMovement(
                ((Number) movement.get("credits")).longValue(),
                ((Number) movement.get("debits")).longValue()
        );
    }

    private List<Long> netMovementForTransactions(String firstPublicId, String secondPublicId) {
        return jdbcTemplate.queryForList("""
                SELECT SUM(CASE WHEN e.direction = 'DEBIT'
                    THEN e.amount_minor ELSE -e.amount_minor END)
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                WHERE t.public_id IN (?, ?)
                GROUP BY e.ledger_account_id
                ORDER BY e.ledger_account_id
                """, Long.class, firstPublicId, secondPublicId);
    }

    private List<String> postingTypes() {
        return jdbcTemplate.queryForList("""
                SELECT posting_type
                FROM ledger_transactions
                ORDER BY id
                """, String.class);
    }

    private long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertNoLedgerRows() {
        assertThat(countRows("ledger_accounts")).isZero();
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    private void installSecondEntryFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION fail_p5_t11_second_entry() RETURNS trigger AS $$
                BEGIN
                    IF NEW.entry_no = 2 THEN
                        RAISE EXCEPTION 'forced second Ledger entry failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_p5_t11_second_entry_trigger
                BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION fail_p5_t11_second_entry()
                """);
    }

    private void dropEntryFailureTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS fail_p5_t11_second_entry_trigger ON ledger_entries
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_p5_t11_second_entry()");
    }

    private static PaymentProviderResult paymentResult(
            ProviderOutcome outcome,
            String suffix
    ) {
        return switch (outcome) {
            case SUCCESS -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    "provider_" + suffix,
                    null,
                    null
            );
            case DECLINED, TECHNICAL_FAILURE -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
                    outcome.name(),
                    "The provider did not complete the payment."
            );
            case UNKNOWN -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
                    "PROVIDER_TIMEOUT",
                    "The provider outcome is unknown."
            );
        };
    }

    private static RefundProviderResult refundResult(
            RefundProviderOutcome outcome,
            String refundPublicId
    ) {
        return switch (outcome) {
            case SUCCESS -> new RefundProviderResult(
                    "SIMULATOR",
                    outcome,
                    refundPublicId.replace("re_accounting_", "provider_"),
                    null,
                    null
            );
            case DECLINED, TECHNICAL_FAILURE -> new RefundProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
                    outcome.name(),
                    "The provider did not complete the refund."
            );
            case UNKNOWN -> new RefundProviderResult(
                    "SIMULATOR",
                    outcome,
                    "provider_pending_unknown",
                    "PROVIDER_TIMEOUT",
                    "The provider outcome is unknown."
            );
        };
    }

    private static List<ExpectedEntry> refundEntries(
            long merchantId,
            String currency,
            long amountMinor
    ) {
        return List.of(
                new ExpectedEntry(
                        1,
                        "MERCHANT_PAYABLE:" + merchantId + ":" + currency,
                        "DEBIT",
                        amountMinor
                ),
                new ExpectedEntry(
                        2,
                        "SYSTEM_CLEARING:" + currency,
                        "CREDIT",
                        amountMinor
                )
        );
    }

    private static java.time.OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record PaymentFixture(
            long merchantId,
            long internalId,
            String publicId,
            String transactionPublicId,
            long amountMinor,
            String currency
    ) {
    }

    private record RefundFixture(
            PaymentFixture payment,
            String publicId,
            long amountMinor
    ) {
    }

    private record ExpectedEntry(
            int entryNo,
            String accountCode,
            String direction,
            long amountMinor
    ) {
    }

    private record PostingSnapshot(
            String postingType,
            String referenceType,
            String referenceId,
            String currency,
            List<EntrySnapshot> entries
    ) {
    }

    private record EntrySnapshot(
            int entryNo,
            String accountCode,
            String direction,
            long amountMinor
    ) {

        private EntrySnapshot opposite() {
            return new EntrySnapshot(
                    entryNo,
                    accountCode,
                    direction.equals("DEBIT") ? "CREDIT" : "DEBIT",
                    amountMinor
            );
        }
    }

    private record AccountMovement(long credits, long debits) {
    }
}
