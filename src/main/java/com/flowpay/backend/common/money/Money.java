package com.flowpay.backend.common.money;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record Money(long amountMinor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money of(long amountMinor, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        String normalizedCode = currencyCode.trim().toUpperCase(Locale.ROOT);
        if (normalizedCode.isEmpty()) {
            throw new IllegalArgumentException("currencyCode must not be blank");
        }
        return new Money(amountMinor, Currency.getInstance(normalizedCode));
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(amountMinor, other.amountMinor), currency);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(amountMinor, other.amountMinor);
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean sameCurrency(Money other) {
        Objects.requireNonNull(other, "other money must not be null");
        return currency.equals(other.currency);
    }

    private void requireSameCurrency(Money other) {
        if (!sameCurrency(other)) {
            throw new IllegalArgumentException("money currencies must match");
        }
    }
}
