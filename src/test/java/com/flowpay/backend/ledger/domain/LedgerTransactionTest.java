package com.flowpay.backend.ledger.domain;

import com.flowpay.backend.common.money.Money;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant CREATED_AT = OCCURRED_AT.plusSeconds(1);

    @Test
    void shouldPostBalancedOneDebitAndOneCredit() {
        LedgerTransaction transaction = postPayment(
                "ltxn_balanced",
                List.of(
                        debit(systemAccount(1L, VND), 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        );

        assertThat(transaction.internalId()).isNull();
        assertThat(transaction.publicId()).isEqualTo("ltxn_balanced");
        assertThat(transaction.postingType()).isEqualTo(LedgerPostingType.PAYMENT_SUCCEEDED);
        assertThat(transaction.businessReference())
                .isEqualTo(LedgerBusinessReference.paymentIntent("pi_payment"));
        assertThat(transaction.currency()).isEqualTo(VND);
        assertThat(transaction.description()).isEqualTo("Payment succeeded");
        assertThat(transaction.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(transaction.createdAt()).isEqualTo(CREATED_AT);
        assertThat(transaction.entries()).extracting(LedgerEntry::entryNo)
                .containsExactly(1, 2);
        assertThat(transaction.entries()).extracting(LedgerEntry::direction)
                .containsExactly(LedgerEntryDirection.DEBIT, LedgerEntryDirection.CREDIT);
        assertThat(transaction.entries()).extracting(LedgerEntry::amountMinor)
                .containsExactly(1_000L, 1_000L);
        assertThat(transaction.entries()).allSatisfy(entry -> {
            assertThat(entry.internalId()).isNull();
            assertThat(entry.ledgerTransactionId()).isNull();
            assertThat(entry.createdAt()).isEqualTo(CREATED_AT);
        });
    }

    @Test
    void shouldAcceptOneDebitAndMultipleCredits() {
        LedgerTransaction transaction = postPayment(
                "ltxn_split_credit",
                List.of(
                        debit(systemAccount(1L, VND), 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 600L, VND),
                        credit(merchantAccount(3L, 16L, VND), 400L, VND)
                )
        );

        assertThat(transaction.entries()).hasSize(3);
        assertThat(transaction.entries()).extracting(LedgerEntry::entryNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    void shouldAcceptMultipleDebitsAndMultipleCredits() {
        LedgerTransaction transaction = postPayment(
                "ltxn_many_entries",
                List.of(
                        debit(systemAccount(1L, VND), 700L, VND),
                        debit(merchantAccount(2L, 15L, VND), 300L, VND),
                        credit(merchantAccount(3L, 16L, VND), 400L, VND),
                        credit(merchantAccount(4L, 17L, VND), 600L, VND)
                )
        );

        assertThat(transaction.entries()).hasSize(4);
    }

    @Test
    void shouldRejectEmptyDebitOnlyAndCreditOnlyEntries() {
        assertThatThrownBy(() -> postPayment("ltxn_empty", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger transaction must contain entries");

        assertThatThrownBy(() -> postPayment(
                "ltxn_debit_only",
                List.of(debit(systemAccount(1L, VND), 1_000L, VND))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one DEBIT and one CREDIT");

        assertThatThrownBy(() -> postPayment(
                "ltxn_credit_only",
                List.of(credit(merchantAccount(2L, 15L, VND), 1_000L, VND))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one DEBIT and one CREDIT");
    }

    @Test
    void shouldRejectUnbalancedEntries() {
        assertThatThrownBy(() -> postPayment(
                "ltxn_unbalanced",
                List.of(
                        debit(systemAccount(1L, VND), 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 999L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger transaction entries must balance");
    }

    @Test
    void shouldRejectZeroAndNegativeEntryAmounts() {
        assertThatThrownBy(() -> debit(systemAccount(1L, VND), 0L, VND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entry amount must be positive");
        assertThatThrownBy(() -> credit(merchantAccount(2L, 15L, VND), -1L, VND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entry amount must be positive");
    }

    @Test
    void shouldRejectArithmeticOverflow() {
        assertThatThrownBy(() -> postPayment(
                "ltxn_overflow",
                List.of(
                        debit(systemAccount(1L, VND), Long.MAX_VALUE, VND),
                        debit(merchantAccount(2L, 15L, VND), 1L, VND),
                        credit(merchantAccount(3L, 16L, VND), Long.MAX_VALUE, VND),
                        credit(merchantAccount(4L, 17L, VND), 1L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger entry totals overflow")
                .hasCauseInstanceOf(ArithmeticException.class);
    }

    @Test
    void shouldAssignDeterministicEntryNumbersAndRejectInvalidRehydratedNumbers() {
        List<LedgerEntry> duplicateNumbers = List.of(
                persistedEntry(101L, 91L, 1L, 1, LedgerEntryDirection.DEBIT, 1_000L, VND),
                persistedEntry(102L, 91L, 2L, 1, LedgerEntryDirection.CREDIT, 1_000L, VND)
        );
        List<LedgerEntry> nonContiguousNumbers = List.of(
                persistedEntry(101L, 91L, 1L, 1, LedgerEntryDirection.DEBIT, 1_000L, VND),
                persistedEntry(102L, 91L, 2L, 3, LedgerEntryDirection.CREDIT, 1_000L, VND)
        );

        assertThatThrownBy(() -> rehydratePayment(duplicateNumbers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entry numbers must be unique");
        assertThatThrownBy(() -> rehydratePayment(nonContiguousNumbers))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deterministic and contiguous");
    }

    @Test
    void shouldRejectMixedAccountAndAmountCurrencies() {
        assertThatThrownBy(() -> postPayment(
                "ltxn_mixed_account_currency",
                List.of(
                        debit(systemAccount(1L, USD), 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ledger account currency must match transaction currency");

        assertThatThrownBy(() -> postPayment(
                "ltxn_mixed_amount_currency",
                List.of(
                        debit(systemAccount(1L, VND), 1_000L, USD),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entry amount currency must match transaction currency");
    }

    @Test
    void shouldRejectClosedOrUnpersistedAccounts() {
        LedgerAccount closedAccount = systemAccount(1L, VND, LedgerAccountStatus.CLOSED);
        LedgerAccount unpersistedAccount = LedgerAccount.createSystemClearing(
                "la_unpersisted",
                VND,
                CREATED_AT
        );

        assertThatThrownBy(() -> postPayment(
                "ltxn_closed_account",
                List.of(
                        debit(closedAccount, 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entries require ACTIVE ledger accounts");

        assertThatThrownBy(() -> postPayment(
                "ltxn_unpersisted_account",
                List.of(
                        debit(unpersistedAccount, 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be persisted");
    }

    @Test
    void shouldAcceptTheFrozenPostingAndReferenceMappings() {
        LedgerTransaction payment = post(
                "ltxn_payment",
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_123")
        );
        LedgerTransaction refund = post(
                "ltxn_refund",
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund("re_123")
        );
        LedgerTransaction reversal = post(
                "ltxn_reversal",
                LedgerPostingType.REVERSAL,
                LedgerBusinessReference.ledgerTransaction("ltxn_original")
        );

        assertThat(payment.businessReference().type())
                .isEqualTo(LedgerReferenceType.PAYMENT_INTENT);
        assertThat(refund.businessReference().type()).isEqualTo(LedgerReferenceType.REFUND);
        assertThat(reversal.businessReference().type())
                .isEqualTo(LedgerReferenceType.LEDGER_TRANSACTION);
    }

    @Test
    void shouldRejectContradictoryPostingAndReferenceMappings() {
        assertThatThrownBy(() -> post(
                "ltxn_wrong_reference",
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.refund("re_123")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires PAYMENT_INTENT reference");
    }

    @Test
    void shouldValidateBusinessReferenceAndTransactionPublicIdPrefixes() {
        assertThatThrownBy(() -> LedgerBusinessReference.paymentIntent("re_wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pi_");
        assertThatThrownBy(() -> LedgerBusinessReference.refund("pi_wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("re_");
        assertThatThrownBy(() -> LedgerBusinessReference.ledgerTransaction("pi_wrong"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ltxn_");
        assertThatThrownBy(() -> post(
                "ledger_transaction_without_prefix",
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_123")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ltxn_");
    }

    @Test
    void shouldRehydrateBalancedEntriesOwnedByTheTransaction() {
        LedgerTransaction transaction = rehydratePayment(List.of(
                persistedEntry(101L, 91L, 1L, 1, LedgerEntryDirection.DEBIT, 1_000L, VND),
                persistedEntry(102L, 91L, 2L, 2, LedgerEntryDirection.CREDIT, 1_000L, VND)
        ));

        assertThat(transaction.internalId()).isEqualTo(91L);
        assertThat(transaction.entries()).extracting(LedgerEntry::internalId)
                .containsExactly(101L, 102L);
    }

    @Test
    void shouldRejectRehydratedEntryOwnedByAnotherTransaction() {
        assertThatThrownBy(() -> rehydratePayment(List.of(
                persistedEntry(101L, 92L, 1L, 1, LedgerEntryDirection.DEBIT, 1_000L, VND),
                persistedEntry(102L, 91L, 2L, 2, LedgerEntryDirection.CREDIT, 1_000L, VND)
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entry ledgerTransactionId must match its aggregate");
    }

    @Test
    void shouldExposeImmutablePostedAggregate() {
        List<LedgerEntryDraft> drafts = new ArrayList<>(List.of(
                debit(systemAccount(1L, VND), 1_000L, VND),
                credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
        ));
        LedgerTransaction transaction = postPayment("ltxn_immutable", drafts);

        drafts.clear();

        assertThat(transaction.entries()).hasSize(2);
        assertThatThrownBy(transaction.entries()::clear)
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(LedgerTransaction.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(LedgerEntry.class.getDeclaredFields())
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(LedgerTransaction.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
        assertThat(LedgerEntry.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    @Test
    void shouldFreezeLedgerPostingReferenceAndDirectionTypes() {
        assertThat(LedgerEntryDirection.values()).containsExactly(
                LedgerEntryDirection.DEBIT,
                LedgerEntryDirection.CREDIT
        );
        assertThat(LedgerPostingType.values()).containsExactly(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerPostingType.REVERSAL
        );
        assertThat(LedgerReferenceType.values()).containsExactly(
                LedgerReferenceType.PAYMENT_INTENT,
                LedgerReferenceType.REFUND,
                LedgerReferenceType.LEDGER_TRANSACTION
        );
    }

    private static LedgerTransaction postPayment(
            String publicId,
            List<LedgerEntryDraft> entries
    ) {
        return LedgerTransaction.post(
                publicId,
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_payment"),
                VND,
                " Payment succeeded ",
                OCCURRED_AT,
                CREATED_AT,
                entries
        );
    }

    private static LedgerTransaction post(
            String publicId,
            LedgerPostingType postingType,
            LedgerBusinessReference reference
    ) {
        return LedgerTransaction.post(
                publicId,
                postingType,
                reference,
                VND,
                null,
                OCCURRED_AT,
                CREATED_AT,
                List.of(
                        debit(systemAccount(1L, VND), 1_000L, VND),
                        credit(merchantAccount(2L, 15L, VND), 1_000L, VND)
                )
        );
    }

    private static LedgerTransaction rehydratePayment(List<LedgerEntry> entries) {
        return LedgerTransaction.rehydrate(
                91L,
                "ltxn_rehydrated",
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerReferenceType.PAYMENT_INTENT,
                "pi_payment",
                VND,
                "Payment succeeded",
                OCCURRED_AT,
                CREATED_AT,
                entries
        );
    }

    private static LedgerEntryDraft debit(
            LedgerAccount account,
            long amountMinor,
            Currency currency
    ) {
        return LedgerEntryDraft.debit(account, new Money(amountMinor, currency));
    }

    private static LedgerEntryDraft credit(
            LedgerAccount account,
            long amountMinor,
            Currency currency
    ) {
        return LedgerEntryDraft.credit(account, new Money(amountMinor, currency));
    }

    private static LedgerEntry persistedEntry(
            long internalId,
            long transactionId,
            long accountId,
            int entryNo,
            LedgerEntryDirection direction,
            long amountMinor,
            Currency accountCurrency
    ) {
        return LedgerEntry.rehydrate(
                internalId,
                transactionId,
                accountId,
                entryNo,
                direction,
                amountMinor,
                accountCurrency,
                CREATED_AT
        );
    }

    private static LedgerAccount systemAccount(long internalId, Currency currency) {
        return systemAccount(internalId, currency, LedgerAccountStatus.ACTIVE);
    }

    private static LedgerAccount systemAccount(
            long internalId,
            Currency currency,
            LedgerAccountStatus status
    ) {
        return LedgerAccount.rehydrate(
                internalId,
                "la_system_" + internalId,
                "SYSTEM_CLEARING:" + currency.getCurrencyCode(),
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                currency,
                status,
                CREATED_AT
        );
    }

    private static LedgerAccount merchantAccount(
            long internalId,
            long merchantId,
            Currency currency
    ) {
        return LedgerAccount.rehydrate(
                internalId,
                "la_merchant_" + internalId,
                "MERCHANT_PAYABLE:" + merchantId + ":" + currency.getCurrencyCode(),
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                merchantId,
                currency,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        );
    }
}
