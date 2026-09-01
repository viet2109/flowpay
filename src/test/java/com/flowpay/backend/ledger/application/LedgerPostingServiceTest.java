package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountStatus;
import com.flowpay.backend.ledger.domain.LedgerAccountType;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerEntryDirection;
import com.flowpay.backend.ledger.domain.LedgerEntryDraft;
import com.flowpay.backend.ledger.domain.LedgerOwnerType;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerPostingServiceTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant CREATED_AT = OCCURRED_AT.plusSeconds(5);
    private static final Money AMOUNT = new Money(1_000_000L, VND);

    @Mock
    private LedgerAccountProvisioningService accountProvisioningService;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerTransactionPublicIdGenerator publicIdGenerator;

    private LedgerPostingService service;
    private LedgerAccount clearing;
    private LedgerAccount payable;

    @BeforeEach
    void setUp() {
        service = new LedgerPostingService(
                accountProvisioningService,
                transactionRepository,
                publicIdGenerator,
                Clock.fixed(CREATED_AT, ZoneOffset.UTC)
        );
        clearing = systemAccount(10L, VND);
        payable = merchantAccount(11L, 15L, VND);
    }

    @Test
    void shouldOwnPaymentAccountingMappingAndReturnCreatedResult() {
        arrangeAccounts();
        when(publicIdGenerator.nextId()).thenReturn("ltxn_payment_candidate");
        when(transactionRepository.tryInsert(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> Optional.of(
                        invocation.getArgument(0, LedgerTransaction.class)
                ));

        LedgerPostingResult result = service.postPaymentSucceeded(
                paymentCommand("pi_payment", AMOUNT, OCCURRED_AT, 15L)
        );

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        assertThat(result.ledgerTransactionPublicId()).isEqualTo("ltxn_payment_candidate");
        LedgerTransaction candidate = captureCandidate();
        assertPostingHeader(
                candidate,
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_payment"),
                "Payment succeeded"
        );
        assertEntries(candidate, clearing, payable);
    }

    @Test
    void shouldOwnRefundAccountingMappingAndReverseEntryDirections() {
        arrangeAccounts();
        when(publicIdGenerator.nextId()).thenReturn("ltxn_refund_candidate");
        when(transactionRepository.tryInsert(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> Optional.of(
                        invocation.getArgument(0, LedgerTransaction.class)
                ));

        LedgerPostingResult result = service.postRefundSucceeded(
                new PostRefundSucceededCommand(15L, "re_refund", AMOUNT, OCCURRED_AT)
        );

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        LedgerTransaction candidate = captureCandidate();
        assertPostingHeader(
                candidate,
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund("re_refund"),
                "Refund succeeded"
        );
        assertEntries(candidate, payable, clearing);
    }

    @Test
    void shouldReturnExistingCanonicalPostingWhenDuplicateIsEquivalent() {
        arrangeAccounts();
        LedgerTransaction existing = paymentPosting(
                "ltxn_existing",
                "pi_duplicate",
                AMOUNT,
                OCCURRED_AT,
                CREATED_AT.minusSeconds(30),
                clearing,
                payable
        );
        when(publicIdGenerator.nextId()).thenReturn("ltxn_unused_candidate");
        when(transactionRepository.tryInsert(any(LedgerTransaction.class)))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByBusinessReference(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_duplicate")
        )).thenReturn(Optional.of(existing));

        LedgerPostingResult result = service.postPaymentSucceeded(
                paymentCommand("pi_duplicate", AMOUNT, OCCURRED_AT, 15L)
        );

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.ALREADY_POSTED);
        assertThat(result.ledgerTransactionPublicId()).isEqualTo("ltxn_existing");
    }

    @Test
    void shouldRejectSemanticallyConflictingDuplicate() {
        arrangeAccounts();
        LedgerTransaction existing = paymentPosting(
                "ltxn_existing",
                "pi_conflict",
                new Money(999_999L, VND),
                OCCURRED_AT,
                CREATED_AT,
                clearing,
                payable
        );
        when(publicIdGenerator.nextId()).thenReturn("ltxn_conflicting_candidate");
        when(transactionRepository.tryInsert(any(LedgerTransaction.class)))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByBusinessReference(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_conflict")
        )).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.postPaymentSucceeded(
                paymentCommand("pi_conflict", AMOUNT, OCCURRED_AT, 15L)
        )).isInstanceOf(LedgerPostingConflictException.class)
                .hasMessage("Existing ledger posting conflicts with supplied financial facts");
    }

    @Test
    void shouldFailSafelyWhenPersistenceCannotClaimOrLoadPosting() {
        arrangeAccounts();
        when(publicIdGenerator.nextId()).thenReturn("ltxn_constraint_failure");
        when(transactionRepository.tryInsert(any(LedgerTransaction.class))).thenThrow(
                new DataIntegrityViolationException(
                        "uq_ledger_transactions_business_reference"
                )
        );

        assertThatThrownBy(() -> service.postPaymentSucceeded(
                paymentCommand("pi_failure", AMOUNT, OCCURRED_AT, 15L)
        )).isInstanceOf(LedgerPostingException.class)
                .isNotInstanceOf(LedgerPostingConflictException.class)
                .hasMessage("Ledger posting could not be completed safely")
                .message()
                .doesNotContain("uq_ledger_transactions_business_reference")
                .doesNotContain("DataIntegrityViolationException");
    }

    @Test
    void commandsShouldRejectInvalidFinancialFacts() {
        assertThatThrownBy(() -> paymentCommand("pi_invalid", AMOUNT, OCCURRED_AT, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("merchantInternalId must be positive");
        assertThatThrownBy(() -> paymentCommand(" ", AMOUNT, OCCURRED_AT, 15L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicId must not be blank");
        assertThatThrownBy(() -> new PostRefundSucceededCommand(
                15L,
                "re_invalid",
                new Money(0L, VND),
                OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amount must be positive");
    }

    @Test
    void shouldValidateBusinessReferenceBeforeProvisioningAccounts() {
        PostPaymentSucceededCommand command = paymentCommand(
                "not-a-payment-id",
                AMOUNT,
                OCCURRED_AT,
                15L
        );

        assertThatThrownBy(() -> service.postPaymentSucceeded(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pi_");
        verifyNoInteractions(
                accountProvisioningService,
                transactionRepository,
                publicIdGenerator
        );
    }

    @Test
    void postingApiShouldExposeOnlySemanticCommandsWithoutAccountingChoices() {
        assertThat(Arrays.stream(LedgerPostingApi.class.getDeclaredMethods()))
                .extracting(java.lang.reflect.Method::getName)
                .containsExactlyInAnyOrder(
                        "postPaymentSucceeded",
                        "postRefundSucceeded"
                );
        assertThat(PostPaymentSucceededCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "merchantInternalId",
                        "paymentPublicId",
                        "amount",
                        "occurredAt"
                );
        assertThat(PostRefundSucceededCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "merchantInternalId",
                        "refundPublicId",
                        "amount",
                        "occurredAt"
                );
        assertThat(Arrays.stream(LedgerPostingApi.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .noneMatch(parameter -> parameter.getPackageName().contains(".ledger.domain"));
    }

    private void arrangeAccounts() {
        when(accountProvisioningService.requireSystemClearing(VND)).thenReturn(clearing);
        when(accountProvisioningService.requireMerchantPayable(15L, VND))
                .thenReturn(payable);
    }

    private LedgerTransaction captureCandidate() {
        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(
                LedgerTransaction.class
        );
        verify(transactionRepository).tryInsert(captor.capture());
        return captor.getValue();
    }

    private static void assertPostingHeader(
            LedgerTransaction transaction,
            LedgerPostingType postingType,
            LedgerBusinessReference reference,
            String description
    ) {
        assertThat(transaction.postingType()).isEqualTo(postingType);
        assertThat(transaction.businessReference()).isEqualTo(reference);
        assertThat(transaction.currency()).isEqualTo(VND);
        assertThat(transaction.description()).isEqualTo(description);
        assertThat(transaction.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(transaction.createdAt()).isEqualTo(CREATED_AT);
    }

    private static void assertEntries(
            LedgerTransaction transaction,
            LedgerAccount debited,
            LedgerAccount credited
    ) {
        assertThat(transaction.entries()).extracting(
                LedgerEntry::ledgerAccountId,
                LedgerEntry::direction,
                LedgerEntry::amountMinor
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        debited.internalId(),
                        LedgerEntryDirection.DEBIT,
                        AMOUNT.amountMinor()
                ),
                org.assertj.core.groups.Tuple.tuple(
                        credited.internalId(),
                        LedgerEntryDirection.CREDIT,
                        AMOUNT.amountMinor()
                )
        );
    }

    private static PostPaymentSucceededCommand paymentCommand(
            String paymentPublicId,
            Money amount,
            Instant occurredAt,
            long merchantId
    ) {
        return new PostPaymentSucceededCommand(
                merchantId,
                paymentPublicId,
                amount,
                occurredAt
        );
    }

    private static LedgerTransaction paymentPosting(
            String transactionPublicId,
            String paymentPublicId,
            Money amount,
            Instant occurredAt,
            Instant createdAt,
            LedgerAccount system,
            LedgerAccount merchant
    ) {
        return LedgerTransaction.post(
                transactionPublicId,
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent(paymentPublicId),
                amount.currency(),
                "Payment succeeded",
                occurredAt,
                createdAt,
                List.of(
                        LedgerEntryDraft.debit(system, amount),
                        LedgerEntryDraft.credit(merchant, amount)
                )
        );
    }

    private static LedgerAccount systemAccount(long internalId, Currency currency) {
        return LedgerAccount.rehydrate(
                internalId,
                "la_system_" + internalId,
                "SYSTEM_CLEARING:" + currency.getCurrencyCode(),
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                currency,
                LedgerAccountStatus.ACTIVE,
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
