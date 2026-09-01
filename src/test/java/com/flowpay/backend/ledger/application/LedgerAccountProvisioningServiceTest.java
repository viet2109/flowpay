package com.flowpay.backend.ledger.application;

import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import com.flowpay.backend.ledger.domain.LedgerAccountStatus;
import com.flowpay.backend.ledger.domain.LedgerAccountType;
import com.flowpay.backend.ledger.domain.LedgerOwnerType;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerAccountProvisioningServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");
    private static final Currency VND = Currency.getInstance("VND");

    @Mock
    private LedgerAccountRepository repository;

    @Mock
    private LedgerAccountPublicIdGenerator publicIdGenerator;

    private LedgerAccountProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new LedgerAccountProvisioningService(
                repository,
                publicIdGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldInsertReloadAndValidateSystemClearingAccount() {
        LedgerAccount canonical = systemAccount(
                10L,
                "la_canonical_system",
                VND,
                LedgerAccountStatus.ACTIVE
        );
        when(publicIdGenerator.nextId()).thenReturn("la_candidate_system");
        when(repository.tryInsert(any(LedgerAccount.class)))
                .thenReturn(Optional.of(canonical));
        when(repository.findByAccountCode(LedgerAccountCode.systemClearing(VND)))
                .thenReturn(Optional.of(canonical));

        LedgerAccount result = service.requireSystemClearing(VND);

        assertThat(result).isSameAs(canonical);
        ArgumentCaptor<LedgerAccount> candidate = ArgumentCaptor.forClass(LedgerAccount.class);
        verify(repository).tryInsert(candidate.capture());
        assertThat(candidate.getValue().internalId()).isNull();
        assertThat(candidate.getValue().publicId()).isEqualTo("la_candidate_system");
        assertThat(candidate.getValue().accountCode())
                .isEqualTo(LedgerAccountCode.systemClearing(VND));
        assertThat(candidate.getValue().createdAt()).isEqualTo(NOW);
        verify(repository).findByAccountCode(LedgerAccountCode.systemClearing(VND));
    }

    @Test
    void shouldReturnWinningCanonicalAccountWhenInsertLosesTheRace() {
        LedgerAccount winner = systemAccount(
                11L,
                "la_winning_system",
                VND,
                LedgerAccountStatus.ACTIVE
        );
        when(publicIdGenerator.nextId()).thenReturn("la_losing_candidate");
        when(repository.tryInsert(any(LedgerAccount.class))).thenReturn(Optional.empty());
        when(repository.findByAccountCode(LedgerAccountCode.systemClearing(VND)))
                .thenReturn(Optional.of(winner));

        LedgerAccount result = service.requireSystemClearing(VND);

        assertThat(result.publicId()).isEqualTo("la_winning_system");
        assertThat(result.internalId()).isEqualTo(11L);
    }

    @Test
    void shouldInsertReloadAndValidateMerchantPayableAccount() {
        LedgerAccount canonical = merchantAccount(
                20L,
                "la_canonical_merchant",
                15L,
                VND,
                LedgerAccountStatus.ACTIVE
        );
        when(publicIdGenerator.nextId()).thenReturn("la_candidate_merchant");
        when(repository.tryInsert(any(LedgerAccount.class)))
                .thenReturn(Optional.of(canonical));
        when(repository.findByAccountCode(LedgerAccountCode.merchantPayable(15L, VND)))
                .thenReturn(Optional.of(canonical));

        LedgerAccount result = service.requireMerchantPayable(15L, VND);

        assertThat(result).isSameAs(canonical);
        assertThat(result.ownerId()).isEqualTo(15L);
        assertThat(result.accountCode())
                .isEqualTo(LedgerAccountCode.merchantPayable(15L, VND));
    }

    @Test
    void shouldFailClosedForConflictingOrInactiveCanonicalMetadata() {
        LedgerAccount wrongIdentity = merchantAccount(
                20L,
                "la_wrong_identity",
                15L,
                VND,
                LedgerAccountStatus.ACTIVE
        );
        LedgerAccount inactive = systemAccount(
                21L,
                "la_inactive_system",
                VND,
                LedgerAccountStatus.CLOSED
        );
        when(publicIdGenerator.nextId()).thenReturn("la_candidate_1", "la_candidate_2");
        when(repository.tryInsert(any(LedgerAccount.class))).thenReturn(Optional.empty());
        when(repository.findByAccountCode(LedgerAccountCode.systemClearing(VND)))
                .thenReturn(Optional.of(wrongIdentity))
                .thenReturn(Optional.of(inactive));

        assertMetadataConflict(() -> service.requireSystemClearing(VND));
        assertMetadataConflict(() -> service.requireSystemClearing(VND));
    }

    @Test
    void shouldFailSafelyWhenCanonicalRowCannotBeLoadedOrMapped() {
        when(publicIdGenerator.nextId()).thenReturn("la_missing", "la_invalid");
        when(repository.tryInsert(any(LedgerAccount.class))).thenReturn(Optional.empty());
        when(repository.findByAccountCode(LedgerAccountCode.systemClearing(VND)))
                .thenReturn(Optional.empty())
                .thenThrow(new IllegalArgumentException("accountCode does not match identity"));

        assertProvisioningFailure(() -> service.requireSystemClearing(VND));
        assertProvisioningFailure(() -> service.requireSystemClearing(VND));
    }

    @Test
    void shouldNotLeakDatabaseConstraintDetails() {
        when(publicIdGenerator.nextId()).thenReturn("la_collision");
        when(repository.tryInsert(any(LedgerAccount.class))).thenThrow(
                new DataIntegrityViolationException("uq_ledger_accounts_public_id")
        );

        assertThatThrownBy(() -> service.requireSystemClearing(VND))
                .isInstanceOf(LedgerAccountProvisioningException.class)
                .hasMessage("Ledger account could not be provisioned safely")
                .message()
                .doesNotContain("uq_ledger_accounts_public_id")
                .doesNotContain("DataIntegrityViolationException");
    }

    @Test
    void shouldRejectInvalidInputBeforeGeneratingOrPersisting() {
        assertThatThrownBy(() -> service.requireSystemClearing(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("currency must not be null");
        assertThatThrownBy(() -> service.requireMerchantPayable(0L, VND))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("merchantInternalId must be positive");

        verifyNoInteractions(publicIdGenerator, repository);
    }

    private static void assertMetadataConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(LedgerAccountProvisioningException.class)
                .hasMessage("Canonical ledger account metadata is inconsistent");
    }

    private static void assertProvisioningFailure(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(LedgerAccountProvisioningException.class)
                .hasMessage("Ledger account could not be provisioned safely");
    }

    private static LedgerAccount systemAccount(
            long internalId,
            String publicId,
            Currency currency,
            LedgerAccountStatus status
    ) {
        return LedgerAccount.rehydrate(
                internalId,
                publicId,
                "SYSTEM_CLEARING:" + currency.getCurrencyCode(),
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                currency,
                status,
                NOW
        );
    }

    private static LedgerAccount merchantAccount(
            long internalId,
            String publicId,
            long merchantId,
            Currency currency,
            LedgerAccountStatus status
    ) {
        return LedgerAccount.rehydrate(
                internalId,
                publicId,
                "MERCHANT_PAYABLE:" + merchantId + ":" + currency.getCurrencyCode(),
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                merchantId,
                currency,
                status,
                NOW
        );
    }
}
