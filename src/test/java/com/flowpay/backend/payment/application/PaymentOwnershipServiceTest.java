package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentOwnershipServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");

    @Mock
    private PaymentMerchantResolver merchantResolver;

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @InjectMocks
    private PaymentOwnershipService service;

    @Test
    void shouldReturnPaymentOwnedByAuthenticatedMerchant() {
        MerchantApiPrincipal principal = principal("mrc_owner");
        ActiveMerchantSnapshot merchant = new ActiveMerchantSnapshot(41L, "mrc_owner");
        PaymentIntent payment = payment(81L, "pi_owned", merchant.internalId());
        when(merchantResolver.resolve(principal)).thenReturn(merchant);
        when(paymentIntentRepository.findByPublicIdAndMerchantId("pi_owned", 41L))
                .thenReturn(Optional.of(payment));

        PaymentIntent result = service.requireOwnedPayment(principal, "pi_owned");

        assertThat(result).isSameAs(payment);
        verify(merchantResolver).resolve(principal);
        verify(paymentIntentRepository).findByPublicIdAndMerchantId("pi_owned", 41L);
    }

    @Test
    void shouldReturnSameNotFoundForMissingAndCrossMerchantPayment() {
        MerchantApiPrincipal principal = principal("mrc_requesting");
        ActiveMerchantSnapshot merchant = new ActiveMerchantSnapshot(52L, "mrc_requesting");
        when(merchantResolver.resolve(principal)).thenReturn(merchant);
        when(paymentIntentRepository.findByPublicIdAndMerchantId("pi_other_owner", 52L))
                .thenReturn(Optional.empty());
        when(paymentIntentRepository.findByPublicIdAndMerchantId("pi_missing", 52L))
                .thenReturn(Optional.empty());

        assertPaymentNotFound(() -> service.requireOwnedPayment(principal, "pi_other_owner"));
        assertPaymentNotFound(() -> service.requireOwnedPayment(principal, "pi_missing"));

        verify(paymentIntentRepository).findByPublicIdAndMerchantId("pi_other_owner", 52L);
        verify(paymentIntentRepository).findByPublicIdAndMerchantId("pi_missing", 52L);
    }

    @Test
    void shouldRejectBlankPaymentPublicIdBeforeResolvingMerchant() {
        assertThatThrownBy(() -> service.requireOwnedPayment(principal("mrc_owner"), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicId must not be blank");

        verifyNoInteractions(merchantResolver, paymentIntentRepository);
    }

    private static MerchantApiPrincipal principal(String merchantPublicId) {
        return new MerchantApiPrincipal(merchantPublicId, "key_payment_access");
    }

    private static PaymentIntent payment(long id, String publicId, long merchantId) {
        return PaymentIntent.rehydrate(
                id,
                publicId,
                merchantId,
                null,
                null,
                Money.of(10_000L, "USD"),
                PaymentStatus.CREATED,
                Money.of(0L, "USD"),
                Money.of(0L, "USD"),
                0L,
                CREATED_AT,
                CREATED_AT
        );
    }

    private static void assertPaymentNotFound(Runnable lookup) {
        assertThatThrownBy(lookup::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("The payment intent was not found.");
                });
    }
}
