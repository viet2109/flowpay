package com.flowpay.backend.common.money;

import org.junit.jupiter.api.Test;

import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void shouldAddMoneyWithTheSameCurrency() {
        Money result = Money.of(1_050L, "usd").add(Money.of(250L, " USD "));

        assertThat(result).isEqualTo(new Money(1_300L, Currency.getInstance("USD")));
    }

    @Test
    void shouldSubtractMoneyWithTheSameCurrency() {
        Money result = Money.of(1_000L, "VND").subtract(Money.of(1_250L, "VND"));

        assertThat(result.amountMinor()).isEqualTo(-250L);
        assertThat(result.currency().getCurrencyCode()).isEqualTo("VND");
    }

    @Test
    void shouldCompareMoneyWithTheSameCurrency() {
        Money smaller = Money.of(999L, "EUR");
        Money equal = Money.of(999L, "EUR");
        Money larger = Money.of(1_000L, "EUR");

        assertThat(smaller.compareTo(larger)).isNegative();
        assertThat(smaller.compareTo(equal)).isZero();
        assertThat(larger.compareTo(smaller)).isPositive();
    }

    @Test
    void shouldRejectOperationsBetweenDifferentCurrencies() {
        Money usd = Money.of(100L, "USD");
        Money eur = Money.of(100L, "EUR");

        assertThat(usd.sameCurrency(eur)).isFalse();
        assertThatThrownBy(() -> usd.add(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("money currencies must match");
        assertThatThrownBy(() -> usd.subtract(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("money currencies must match");
        assertThatThrownBy(() -> usd.compareTo(eur))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("money currencies must match");
    }

    @Test
    void shouldRejectInvalidCurrencyCodes() {
        assertThatThrownBy(() -> Money.of(100L, "not-a-currency"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(100L, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("currencyCode must not be blank");
        assertThatThrownBy(() -> Money.of(100L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("currencyCode must not be null");
    }

    @Test
    void shouldIdentifyPositivePaymentAmountsWithoutForbiddingZeroBalances() {
        assertThat(Money.of(1L, "VND").isPositive()).isTrue();
        assertThat(Money.of(0L, "VND").isPositive()).isFalse();
        assertThat(Money.of(-1L, "VND").isPositive()).isFalse();
    }

    @Test
    void shouldRejectMinorUnitOverflow() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "USD").add(Money.of(1L, "USD")))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MIN_VALUE, "USD").subtract(Money.of(1L, "USD")))
                .isInstanceOf(ArithmeticException.class);
    }
}
