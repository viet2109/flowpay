package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import com.flowpay.backend.ledger.domain.LedgerAccountStatus;
import com.flowpay.backend.ledger.domain.LedgerAccountType;
import com.flowpay.backend.ledger.domain.LedgerOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LedgerAccountProvisioningService {

    private static final String PROVISIONING_FAILURE_MESSAGE =
            "Ledger account could not be provisioned safely";
    private static final String METADATA_CONFLICT_MESSAGE =
            "Canonical ledger account metadata is inconsistent";

    private final LedgerAccountRepository repository;
    private final LedgerAccountPublicIdGenerator publicIdGenerator;
    private final Clock clock;

    @Transactional
    public LedgerAccount requireSystemClearing(Currency currency) {
        Currency requiredCurrency = Objects.requireNonNull(
                currency,
                "currency must not be null"
        );
        LedgerAccountCode accountCode = LedgerAccountCode.systemClearing(requiredCurrency);
        LedgerAccount candidate = LedgerAccount.createSystemClearing(
                publicIdGenerator.nextId(),
                requiredCurrency,
                clock.instant()
        );
        LedgerAccount canonical = insertAndLoadCanonical(candidate, accountCode);
        validateCanonical(
                canonical,
                accountCode,
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                requiredCurrency
        );
        return canonical;
    }

    @Transactional
    public LedgerAccount requireMerchantPayable(
            long merchantInternalId,
            Currency currency
    ) {
        Currency requiredCurrency = Objects.requireNonNull(
                currency,
                "currency must not be null"
        );
        LedgerAccountCode accountCode = LedgerAccountCode.merchantPayable(
                merchantInternalId,
                requiredCurrency
        );
        LedgerAccount candidate = LedgerAccount.createMerchantPayable(
                publicIdGenerator.nextId(),
                merchantInternalId,
                requiredCurrency,
                clock.instant()
        );
        LedgerAccount canonical = insertAndLoadCanonical(candidate, accountCode);
        validateCanonical(
                canonical,
                accountCode,
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                merchantInternalId,
                requiredCurrency
        );
        return canonical;
    }

    private LedgerAccount insertAndLoadCanonical(
            LedgerAccount candidate,
            LedgerAccountCode accountCode
    ) {
        try {
            repository.tryInsert(candidate);
            return repository.findByAccountCode(accountCode).orElseThrow(() ->
                    new LedgerAccountProvisioningException(PROVISIONING_FAILURE_MESSAGE)
            );
        } catch (LedgerAccountProvisioningException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LedgerAccountProvisioningException(
                    PROVISIONING_FAILURE_MESSAGE,
                    exception
            );
        }
    }

    private static void validateCanonical(
            LedgerAccount canonical,
            LedgerAccountCode expectedCode,
            LedgerAccountType expectedAccountType,
            LedgerOwnerType expectedOwnerType,
            Long expectedOwnerId,
            Currency expectedCurrency
    ) {
        if (!expectedCode.equals(canonical.accountCode())
                || canonical.accountType() != expectedAccountType
                || canonical.ownerType() != expectedOwnerType
                || !Objects.equals(canonical.ownerId(), expectedOwnerId)
                || !expectedCurrency.equals(canonical.currency())
                || canonical.status() != LedgerAccountStatus.ACTIVE) {
            throw new LedgerAccountProvisioningException(METADATA_CONFLICT_MESSAGE);
        }
    }
}
