package com.flowpay.backend.ledger.domain;

import java.util.Currency;
import java.util.Objects;

public final class LedgerAccountCode {

    private static final String SYSTEM_CLEARING_PREFIX = "SYSTEM_CLEARING:";
    private static final String MERCHANT_PAYABLE_PREFIX = "MERCHANT_PAYABLE:";

    private final String value;

    private LedgerAccountCode(String value) {
        this.value = value;
    }

    public static LedgerAccountCode systemClearing(Currency currency) {
        return new LedgerAccountCode(
                SYSTEM_CLEARING_PREFIX + requireCurrency(currency).getCurrencyCode()
        );
    }

    public static LedgerAccountCode merchantPayable(long merchantInternalId, Currency currency) {
        if (merchantInternalId <= 0) {
            throw new IllegalArgumentException("merchantInternalId must be positive");
        }
        return new LedgerAccountCode(
                MERCHANT_PAYABLE_PREFIX
                        + merchantInternalId
                        + ":"
                        + requireCurrency(currency).getCurrencyCode()
        );
    }

    private static Currency requireCurrency(Currency currency) {
        return Objects.requireNonNull(currency, "currency must not be null");
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof LedgerAccountCode that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
