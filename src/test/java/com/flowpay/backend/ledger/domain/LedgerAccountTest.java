package com.flowpay.backend.ledger.domain;

import com.flowpay.backend.common.money.Money;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAccountTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Currency VND = Currency.getInstance("VND");

    @Test
    void shouldCreateActiveSystemClearingAccount() {
        LedgerAccount account = LedgerAccount.createSystemClearing(
                "la_system_vnd",
                VND,
                CREATED_AT
        );

        assertThat(account.internalId()).isNull();
        assertThat(account.publicId()).isEqualTo("la_system_vnd");
        assertThat(account.accountCode().value()).isEqualTo("SYSTEM_CLEARING:VND");
        assertThat(account.accountType()).isEqualTo(LedgerAccountType.SYSTEM_CLEARING);
        assertThat(account.ownerType()).isEqualTo(LedgerOwnerType.SYSTEM);
        assertThat(account.ownerId()).isNull();
        assertThat(account.currency()).isEqualTo(VND);
        assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(account.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldCreateActiveMerchantPayableAccount() {
        LedgerAccount account = LedgerAccount.createMerchantPayable(
                "la_merchant_15_vnd",
                15L,
                VND,
                CREATED_AT
        );

        assertThat(account.accountCode().value()).isEqualTo("MERCHANT_PAYABLE:15:VND");
        assertThat(account.accountType()).isEqualTo(LedgerAccountType.MERCHANT_PAYABLE);
        assertThat(account.ownerType()).isEqualTo(LedgerOwnerType.MERCHANT);
        assertThat(account.ownerId()).isEqualTo(15L);
        assertThat(account.currency()).isEqualTo(VND);
        assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
    }

    @Test
    void shouldGenerateDeterministicCodesForEquivalentAccountIdentities() {
        LedgerAccount firstSystem = LedgerAccount.createSystemClearing(
                "la_system_first",
                VND,
                CREATED_AT
        );
        LedgerAccount secondSystem = LedgerAccount.createSystemClearing(
                "la_system_second",
                VND,
                CREATED_AT.plusSeconds(1)
        );
        LedgerAccount firstMerchant = LedgerAccount.createMerchantPayable(
                "la_merchant_first",
                15L,
                VND,
                CREATED_AT
        );
        LedgerAccount secondMerchant = LedgerAccount.createMerchantPayable(
                "la_merchant_second",
                15L,
                VND,
                CREATED_AT.plusSeconds(1)
        );

        assertThat(firstSystem.accountCode())
                .isEqualTo(secondSystem.accountCode())
                .hasToString("SYSTEM_CLEARING:VND");
        assertThat(firstMerchant.accountCode())
                .isEqualTo(secondMerchant.accountCode())
                .hasToString("MERCHANT_PAYABLE:15:VND");
    }

    @Test
    void shouldUseTheSharedCanonicalCurrencyConvention() {
        Currency normalizedCurrency = Money.of(0L, " vnd ").currency();

        LedgerAccount account = LedgerAccount.createMerchantPayable(
                "la_currency",
                42L,
                normalizedCurrency,
                CREATED_AT
        );

        assertThat(account.currency().getCurrencyCode()).isEqualTo("VND");
        assertThat(account.accountCode().value()).isEqualTo("MERCHANT_PAYABLE:42:VND");
    }

    @Test
    void shouldRejectInvalidOwnerCombinationsDuringRehydration() {
        assertThatThrownBy(() -> LedgerAccount.rehydrate(
                1L,
                "la_invalid_system",
                "SYSTEM_CLEARING:VND",
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.MERCHANT,
                15L,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SYSTEM_CLEARING");

        assertThatThrownBy(() -> LedgerAccount.rehydrate(
                1L,
                "la_invalid_merchant",
                "MERCHANT_PAYABLE:15:VND",
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                null,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MERCHANT_PAYABLE");
    }

    @Test
    void shouldRejectInvalidMerchantInternalIds() {
        assertThatThrownBy(() -> LedgerAccount.createMerchantPayable(
                "la_zero_owner",
                0L,
                VND,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> LedgerAccount.createMerchantPayable(
                "la_negative_owner",
                -1L,
                VND,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void shouldRejectNullCurrencyAndInvalidPublicIds() {
        assertThatThrownBy(() -> LedgerAccount.createSystemClearing(
                "la_null_currency",
                null,
                CREATED_AT
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("currency must not be null");

        assertThatThrownBy(() -> LedgerAccount.createSystemClearing(
                "account_without_prefix",
                VND,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("la_");
    }

    @Test
    void shouldRejectPersistedCodeThatContradictsAccountIdentity() {
        assertThatThrownBy(() -> LedgerAccount.rehydrate(
                1L,
                "la_mismatched_code",
                "SYSTEM_CLEARING:USD",
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("accountCode does not match account identity");

        assertThatThrownBy(() -> LedgerAccount.rehydrate(
                1L,
                "la_missing_code",
                null,
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("accountCode must not be null");
    }

    @Test
    void shouldRehydrateAClosedAccountWithoutExposingLifecycleMutation() {
        LedgerAccount account = LedgerAccount.rehydrate(
                10L,
                "la_closed",
                "MERCHANT_PAYABLE:15:VND",
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                15L,
                VND,
                LedgerAccountStatus.CLOSED,
                CREATED_AT
        );

        assertThat(account.internalId()).isEqualTo(10L);
        assertThat(account.status()).isEqualTo(LedgerAccountStatus.CLOSED);
        assertThat(LedgerAccount.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(LedgerAccount.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    @Test
    void shouldFreezePhaseFiveAccountTypesAndStatuses() {
        assertThat(LedgerAccountType.values()).containsExactly(
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerAccountType.MERCHANT_PAYABLE
        );
        assertThat(LedgerOwnerType.values()).containsExactly(
                LedgerOwnerType.SYSTEM,
                LedgerOwnerType.MERCHANT
        );
        assertThat(LedgerAccountStatus.values()).containsExactly(
                LedgerAccountStatus.ACTIVE,
                LedgerAccountStatus.CLOSED
        );
    }
}
