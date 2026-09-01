package com.flowpay.backend.ledger.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerEntryDraft;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LedgerPostingService implements LedgerPostingApi {

    private static final String PAYMENT_DESCRIPTION = "Payment succeeded";
    private static final String REFUND_DESCRIPTION = "Refund succeeded";
    private static final String POSTING_FAILURE_MESSAGE =
            "Ledger posting could not be completed safely";
    private static final String POSTING_CONFLICT_MESSAGE =
            "Existing ledger posting conflicts with supplied financial facts";

    private final LedgerAccountProvisioningService accountProvisioningService;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerTransactionPublicIdGenerator publicIdGenerator;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingResult postPaymentSucceeded(PostPaymentSucceededCommand command) {
        PostPaymentSucceededCommand value = Objects.requireNonNull(
                command,
                "command must not be null"
        );
        LedgerBusinessReference reference = LedgerBusinessReference.paymentIntent(
                value.paymentPublicId()
        );
        Money amount = value.amount();
        LedgerAccount clearing = accountProvisioningService.requireSystemClearing(
                amount.currency()
        );
        LedgerAccount payable = accountProvisioningService.requireMerchantPayable(
                value.merchantInternalId(),
                amount.currency()
        );
        LedgerTransaction candidate = buildPosting(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                reference,
                amount,
                PAYMENT_DESCRIPTION,
                value.occurredAt(),
                LedgerEntryDraft.debit(clearing, amount),
                LedgerEntryDraft.credit(payable, amount)
        );
        return claim(candidate);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerPostingResult postRefundSucceeded(PostRefundSucceededCommand command) {
        PostRefundSucceededCommand value = Objects.requireNonNull(
                command,
                "command must not be null"
        );
        LedgerBusinessReference reference = LedgerBusinessReference.refund(
                value.refundPublicId()
        );
        Money amount = value.amount();
        LedgerAccount clearing = accountProvisioningService.requireSystemClearing(
                amount.currency()
        );
        LedgerAccount payable = accountProvisioningService.requireMerchantPayable(
                value.merchantInternalId(),
                amount.currency()
        );
        LedgerTransaction candidate = buildPosting(
                LedgerPostingType.REFUND_SUCCEEDED,
                reference,
                amount,
                REFUND_DESCRIPTION,
                value.occurredAt(),
                LedgerEntryDraft.debit(payable, amount),
                LedgerEntryDraft.credit(clearing, amount)
        );
        return claim(candidate);
    }

    private LedgerTransaction buildPosting(
            LedgerPostingType postingType,
            LedgerBusinessReference businessReference,
            Money amount,
            String description,
            Instant occurredAt,
            LedgerEntryDraft debit,
            LedgerEntryDraft credit
    ) {
        return LedgerTransaction.post(
                publicIdGenerator.nextId(),
                postingType,
                businessReference,
                amount.currency(),
                description,
                occurredAt,
                clock.instant(),
                List.of(debit, credit)
        );
    }

    private LedgerPostingResult claim(LedgerTransaction candidate) {
        LedgerTransaction persisted;
        try {
            persisted = transactionRepository.tryInsert(candidate).orElse(null);
            if (persisted != null) {
                return result(LedgerPostingOutcome.CREATED, persisted);
            }
            persisted = transactionRepository.findByBusinessReference(
                    candidate.postingType(),
                    candidate.businessReference()
            ).orElseThrow(() -> new LedgerPostingException(POSTING_FAILURE_MESSAGE));
        } catch (LedgerPostingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LedgerPostingException(POSTING_FAILURE_MESSAGE, exception);
        }

        if (!semanticallyEquivalent(candidate, persisted)) {
            throw new LedgerPostingConflictException(POSTING_CONFLICT_MESSAGE);
        }
        return result(LedgerPostingOutcome.ALREADY_POSTED, persisted);
    }

    private static boolean semanticallyEquivalent(
            LedgerTransaction candidate,
            LedgerTransaction persisted
    ) {
        return candidate.postingType() == persisted.postingType()
                && candidate.businessReference().equals(persisted.businessReference())
                && candidate.currency().equals(persisted.currency())
                && Objects.equals(candidate.description(), persisted.description())
                && candidate.occurredAt().equals(persisted.occurredAt())
                && equivalentEntries(candidate.entries(), persisted.entries());
    }

    private static boolean equivalentEntries(
            List<LedgerEntry> candidate,
            List<LedgerEntry> persisted
    ) {
        if (candidate.size() != persisted.size()) {
            return false;
        }
        for (int index = 0; index < candidate.size(); index++) {
            LedgerEntry expected = candidate.get(index);
            LedgerEntry actual = persisted.get(index);
            if (expected.ledgerAccountId() != actual.ledgerAccountId()
                    || expected.direction() != actual.direction()
                    || expected.amountMinor() != actual.amountMinor()) {
                return false;
            }
        }
        return true;
    }

    private static LedgerPostingResult result(
            LedgerPostingOutcome outcome,
            LedgerTransaction transaction
    ) {
        return new LedgerPostingResult(outcome, transaction.publicId());
    }
}
