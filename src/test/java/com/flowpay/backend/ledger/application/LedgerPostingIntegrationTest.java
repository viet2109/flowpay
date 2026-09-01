package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerEntryDirection;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LedgerPostingIntegrationTest extends PostgresIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private LedgerPostingApi postingApi;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerAccountRepository accountRepository;

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
    void shouldPostBalancedPaymentWithLedgerOwnedDirections() {
        LedgerPostingResult result = postPayment(
                "pi_payment_vnd",
                15L,
                1_000_000L,
                VND,
                OCCURRED_AT
        );

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(result.ledgerTransactionPublicId()).startsWith("ltxn_");
        LedgerTransaction posting = findPayment("pi_payment_vnd");
        LedgerAccount clearing = findAccount(LedgerAccountCode.systemClearing(VND));
        LedgerAccount payable = findAccount(LedgerAccountCode.merchantPayable(15L, VND));

        assertHeader(
                posting,
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_payment_vnd"),
                VND,
                "Payment succeeded"
        );
        assertEntries(posting, clearing, payable, 1_000_000L);
        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
    }

    @Test
    void shouldPostBalancedRefundWithExactReverseDirections() {
        LedgerPostingResult result = postRefund(
                "re_refund_vnd",
                15L,
                300_000L,
                VND,
                OCCURRED_AT
        );

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        LedgerTransaction posting = findRefund("re_refund_vnd");
        LedgerAccount clearing = findAccount(LedgerAccountCode.systemClearing(VND));
        LedgerAccount payable = findAccount(LedgerAccountCode.merchantPayable(15L, VND));

        assertHeader(
                posting,
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund("re_refund_vnd"),
                VND,
                "Refund succeeded"
        );
        assertEntries(posting, payable, clearing, 300_000L);
    }

    @Test
    void shouldResolveDifferentPayableAccountsForMerchantAndCurrencyDimensions() {
        postPayment("pi_merchant_15_vnd", 15L, 1_000L, VND, OCCURRED_AT);
        postPayment("pi_merchant_29_vnd", 29L, 1_000L, VND, OCCURRED_AT);
        postPayment("pi_merchant_15_usd", 15L, 1_000L, USD, OCCURRED_AT);

        LedgerAccount merchant15Vnd = findAccount(
                LedgerAccountCode.merchantPayable(15L, VND)
        );
        LedgerAccount merchant29Vnd = findAccount(
                LedgerAccountCode.merchantPayable(29L, VND)
        );
        LedgerAccount merchant15Usd = findAccount(
                LedgerAccountCode.merchantPayable(15L, USD)
        );
        LedgerAccount clearingVnd = findAccount(LedgerAccountCode.systemClearing(VND));
        LedgerAccount clearingUsd = findAccount(LedgerAccountCode.systemClearing(USD));

        assertThat(Set.of(
                merchant15Vnd.internalId(),
                merchant29Vnd.internalId(),
                merchant15Usd.internalId()
        )).hasSize(3);
        assertThat(findPayment("pi_merchant_15_vnd").entries().get(1).ledgerAccountId())
                .isEqualTo(merchant15Vnd.internalId());
        assertThat(findPayment("pi_merchant_29_vnd").entries().get(1).ledgerAccountId())
                .isEqualTo(merchant29Vnd.internalId());
        assertThat(findPayment("pi_merchant_15_usd").entries().get(1).ledgerAccountId())
                .isEqualTo(merchant15Usd.internalId());
        assertThat(clearingVnd.internalId()).isNotEqualTo(clearingUsd.internalId());
        assertThat(countRows("ledger_accounts")).isEqualTo(5L);
    }

    @Test
    void shouldReturnCanonicalTransactionsWithoutAddingEntriesForEquivalentDuplicates() {
        LedgerPostingResult firstPayment = postPayment(
                "pi_duplicate",
                15L,
                1_000_000L,
                VND,
                OCCURRED_AT
        );
        LedgerPostingResult repeatedPayment = postPayment(
                "pi_duplicate",
                15L,
                1_000_000L,
                VND,
                OCCURRED_AT
        );
        LedgerPostingResult firstRefund = postRefund(
                "re_duplicate",
                15L,
                300_000L,
                VND,
                OCCURRED_AT
        );
        LedgerPostingResult repeatedRefund = postRefund(
                "re_duplicate",
                15L,
                300_000L,
                VND,
                OCCURRED_AT
        );

        assertThat(firstPayment.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(repeatedPayment.outcome()).isEqualTo(
                LedgerPostingOutcome.ALREADY_POSTED
        );
        assertThat(repeatedPayment.ledgerTransactionPublicId())
                .isEqualTo(firstPayment.ledgerTransactionPublicId());
        assertThat(firstRefund.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(repeatedRefund.outcome()).isEqualTo(
                LedgerPostingOutcome.ALREADY_POSTED
        );
        assertThat(repeatedRefund.ledgerTransactionPublicId())
                .isEqualTo(firstRefund.ledgerTransactionPublicId());
        assertThat(countRows("ledger_transactions")).isEqualTo(2L);
        assertThat(countRows("ledger_entries")).isEqualTo(4L);
    }

    @Test
    void shouldRejectPaymentDuplicateWithDifferentFinancialSemantics() {
        postPayment("pi_conflict", 15L, 1_000_000L, VND, OCCURRED_AT);

        assertPostingConflict(() -> postPayment(
                "pi_conflict",
                15L,
                999_999L,
                VND,
                OCCURRED_AT
        ));
        assertPostingConflict(() -> postPayment(
                "pi_conflict",
                15L,
                1_000_000L,
                USD,
                OCCURRED_AT
        ));
        assertPostingConflict(() -> postPayment(
                "pi_conflict",
                29L,
                1_000_000L,
                VND,
                OCCURRED_AT
        ));
        assertPostingConflict(() -> postPayment(
                "pi_conflict",
                15L,
                1_000_000L,
                VND,
                OCCURRED_AT.plusSeconds(1)
        ));

        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
        assertThat(countRows("ledger_accounts")).isEqualTo(2L);
    }

    @Test
    void shouldRejectRefundDuplicateWithDifferentAmount() {
        postRefund("re_conflict", 15L, 300_000L, VND, OCCURRED_AT);

        assertPostingConflict(() -> postRefund(
                "re_conflict",
                15L,
                299_999L,
                VND,
                OCCURRED_AT
        ));

        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
    }

    @Test
    void shouldRequireCallerOwnedTransaction() {
        PostPaymentSucceededCommand command = new PostPaymentSucceededCommand(
                15L,
                "pi_requires_transaction",
                new Money(1_000L, VND),
                OCCURRED_AT
        );

        assertThatThrownBy(() -> postingApi.postPaymentSucceeded(command))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    private LedgerPostingResult postPayment(
            String paymentPublicId,
            long merchantId,
            long amountMinor,
            Currency currency,
            Instant occurredAt
    ) {
        return inTransaction(() -> postingApi.postPaymentSucceeded(
                new PostPaymentSucceededCommand(
                        merchantId,
                        paymentPublicId,
                        new Money(amountMinor, currency),
                        occurredAt
                )
        ));
    }

    private LedgerPostingResult postRefund(
            String refundPublicId,
            long merchantId,
            long amountMinor,
            Currency currency,
            Instant occurredAt
    ) {
        return inTransaction(() -> postingApi.postRefundSucceeded(
                new PostRefundSucceededCommand(
                        merchantId,
                        refundPublicId,
                        new Money(amountMinor, currency),
                        occurredAt
                )
        ));
    }

    private LedgerTransaction findPayment(String paymentPublicId) {
        return findPosting(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent(paymentPublicId)
        );
    }

    private LedgerTransaction findRefund(String refundPublicId) {
        return findPosting(
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund(refundPublicId)
        );
    }

    private LedgerTransaction findPosting(
            LedgerPostingType postingType,
            LedgerBusinessReference reference
    ) {
        return inTransaction(() -> transactionRepository.findByBusinessReference(
                postingType,
                reference
        ).orElseThrow());
    }

    private LedgerAccount findAccount(LedgerAccountCode code) {
        return inTransaction(() -> accountRepository.findByAccountCode(code).orElseThrow());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> operation) {
        T result = new TransactionTemplate(transactionManager).execute(
                status -> operation.get()
        );
        return java.util.Objects.requireNonNull(result, "transaction result must not be null");
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static void assertHeader(
            LedgerTransaction posting,
            LedgerPostingType postingType,
            LedgerBusinessReference reference,
            Currency currency,
            String description
    ) {
        assertThat(posting.publicId()).startsWith("ltxn_");
        assertThat(posting.postingType()).isEqualTo(postingType);
        assertThat(posting.businessReference()).isEqualTo(reference);
        assertThat(posting.currency()).isEqualTo(currency);
        assertThat(posting.description()).isEqualTo(description);
        assertThat(posting.occurredAt()).isEqualTo(OCCURRED_AT);
    }

    private static void assertEntries(
            LedgerTransaction posting,
            LedgerAccount debited,
            LedgerAccount credited,
            long amountMinor
    ) {
        assertThat(posting.entries()).extracting(
                LedgerEntry::entryNo,
                LedgerEntry::ledgerAccountId,
                LedgerEntry::direction,
                LedgerEntry::amountMinor
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        1,
                        debited.internalId(),
                        LedgerEntryDirection.DEBIT,
                        amountMinor
                ),
                org.assertj.core.groups.Tuple.tuple(
                        2,
                        credited.internalId(),
                        LedgerEntryDirection.CREDIT,
                        amountMinor
                )
        );
        assertThat(posting.entries())
                .extracting(LedgerEntry::amountMinor)
                .containsExactly(amountMinor, amountMinor);
    }

    private static void assertPostingConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(LedgerPostingConflictException.class)
                .hasMessage("Existing ledger posting conflicts with supplied financial facts");
    }
}
