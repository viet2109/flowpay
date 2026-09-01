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
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerReversalServiceTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Instant ORIGINAL_OCCURRED_AT =
            Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant REVERSAL_OCCURRED_AT =
            Instant.parse("2026-09-01T09:00:00Z");
    private static final Instant REVERSAL_CREATED_AT =
            Instant.parse("2026-09-01T09:00:01Z");

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerTransactionPublicIdGenerator publicIdGenerator;

    @Mock
    private LedgerPostingService postingService;

    private LedgerReversalService service;

    @BeforeEach
    void setUp() {
        service = new LedgerReversalService(
                transactionRepository,
                publicIdGenerator,
                postingService,
                Clock.fixed(REVERSAL_CREATED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldBuildFullReversalAndDelegateToIdempotentClaimEngine() {
        LedgerTransaction original = originalPayment();
        ReverseLedgerTransactionCommand command = command(
                original.publicId(),
                " Correct duplicate posting "
        );
        when(transactionRepository.findByPublicId(original.publicId()))
                .thenReturn(Optional.of(original));
        when(publicIdGenerator.nextId()).thenReturn("ltxn_reversal_candidate");
        when(postingService.claim(any(LedgerTransaction.class))).thenReturn(
                new LedgerPostingResult(
                        LedgerPostingOutcome.CREATED,
                        "ltxn_reversal_candidate"
                )
        );

        LedgerPostingResult result = service.reverse(command);

        assertThat(result.outcome()).isEqualTo(LedgerPostingOutcome.CREATED);
        ArgumentCaptor<LedgerTransaction> captor = ArgumentCaptor.forClass(
                LedgerTransaction.class
        );
        verify(postingService).claim(captor.capture());
        LedgerTransaction reversal = captor.getValue();
        assertThat(reversal.publicId()).isEqualTo("ltxn_reversal_candidate");
        assertThat(reversal.postingType()).isEqualTo(LedgerPostingType.REVERSAL);
        assertThat(reversal.businessReference()).isEqualTo(
                LedgerBusinessReference.ledgerTransaction(original.publicId())
        );
        assertThat(reversal.currency()).isEqualTo(VND);
        assertThat(reversal.description()).isEqualTo("Correct duplicate posting");
        assertThat(reversal.occurredAt()).isEqualTo(REVERSAL_OCCURRED_AT);
        assertThat(reversal.createdAt()).isEqualTo(REVERSAL_CREATED_AT);
        assertThat(reversal.entries()).extracting(
                LedgerEntry::ledgerAccountId,
                LedgerEntry::direction,
                LedgerEntry::amountMinor
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple(
                        10L,
                        LedgerEntryDirection.CREDIT,
                        1_000L
                ),
                org.assertj.core.groups.Tuple.tuple(
                        11L,
                        LedgerEntryDirection.DEBIT,
                        1_000L
                )
        );
    }

    @Test
    void shouldRejectUnknownOriginalBeforeGeneratingOrClaiming() {
        when(transactionRepository.findByPublicId("ltxn_missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reverse(command(
                "ltxn_missing",
                "Missing original"
        ))).isInstanceOf(LedgerTransactionNotFoundException.class)
                .hasMessage("The original ledger transaction was not found");
        verifyNoInteractions(publicIdGenerator, postingService);
    }

    @Test
    void shouldRejectReverseAReversalBeforeGeneratingOrClaiming() {
        LedgerTransaction reversal = originalPayment().reverse(
                "ltxn_existing_reversal",
                "Existing reversal",
                REVERSAL_OCCURRED_AT,
                REVERSAL_CREATED_AT
        );
        when(transactionRepository.findByPublicId(reversal.publicId()))
                .thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.reverse(command(
                reversal.publicId(),
                "Forbidden reversal chain"
        ))).isInstanceOf(LedgerTransactionNotReversibleException.class)
                .hasMessage("A reversal ledger transaction cannot be reversed");
        verifyNoInteractions(publicIdGenerator, postingService);
    }

    @Test
    void shouldHideCorruptPersistenceDetails() {
        when(transactionRepository.findByPublicId("ltxn_corrupt")).thenThrow(
                new DataIntegrityViolationException("ledger_entries_amount_minor")
        );

        assertThatThrownBy(() -> service.reverse(command(
                "ltxn_corrupt",
                "Corrupt original"
        ))).isInstanceOf(LedgerReversalException.class)
                .hasMessage("The original ledger transaction cannot be safely reversed")
                .message()
                .doesNotContain("ledger_entries_amount_minor")
                .doesNotContain("DataIntegrityViolationException");
        verifyNoInteractions(publicIdGenerator, postingService);
    }

    @Test
    void commandShouldRejectInvalidInput() {
        assertThatThrownBy(() -> new ReverseLedgerTransactionCommand(
                "pi_wrong",
                "Correction",
                REVERSAL_OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ltxn_");
        assertThatThrownBy(() -> new ReverseLedgerTransactionCommand(
                "ltxn_original",
                " ",
                REVERSAL_OCCURRED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("description must not be blank");
        assertThatThrownBy(() -> new ReverseLedgerTransactionCommand(
                "ltxn_original",
                "Correction",
                null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("occurredAt must not be null");
    }

    @Test
    void postingFailureShouldPropagateWithoutASecondClaim() {
        LedgerTransaction original = originalPayment();
        when(transactionRepository.findByPublicId(original.publicId()))
                .thenReturn(Optional.of(original));
        when(publicIdGenerator.nextId()).thenReturn("ltxn_failed_reversal");
        when(postingService.claim(any())).thenThrow(
                new LedgerPostingException("Ledger posting could not be completed safely")
        );

        assertThatThrownBy(() -> service.reverse(command(
                original.publicId(),
                "Failed reversal"
        ))).isInstanceOf(LedgerPostingException.class)
                .hasMessage("Ledger posting could not be completed safely");
        verify(postingService).claim(any());
        verify(postingService, never()).postPaymentSucceeded(any());
        verify(postingService, never()).postRefundSucceeded(any());
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

    private static LedgerTransaction originalPayment() {
        LedgerAccount clearing = account(
                10L,
                "la_system_vnd",
                "SYSTEM_CLEARING:VND",
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null
        );
        LedgerAccount payable = account(
                11L,
                "la_merchant_vnd",
                "MERCHANT_PAYABLE:15:VND",
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                15L
        );
        Money amount = new Money(1_000L, VND);
        return LedgerTransaction.post(
                "ltxn_original_payment",
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_original_payment"),
                VND,
                "Payment succeeded",
                ORIGINAL_OCCURRED_AT,
                ORIGINAL_OCCURRED_AT.plusSeconds(1),
                List.of(
                        LedgerEntryDraft.debit(clearing, amount),
                        LedgerEntryDraft.credit(payable, amount)
                )
        );
    }

    private static LedgerAccount account(
            long internalId,
            String publicId,
            String accountCode,
            LedgerAccountType accountType,
            LedgerOwnerType ownerType,
            Long ownerId
    ) {
        return LedgerAccount.rehydrate(
                internalId,
                publicId,
                accountCode,
                accountType,
                ownerType,
                ownerId,
                VND,
                LedgerAccountStatus.ACTIVE,
                ORIGINAL_OCCURRED_AT
        );
    }
}
