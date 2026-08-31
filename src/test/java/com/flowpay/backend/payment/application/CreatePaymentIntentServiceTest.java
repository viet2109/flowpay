package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreatePaymentIntentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T09:00:00Z");
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_payment_owner",
            "key_payment_create"
    );
    private static final ActiveMerchantSnapshot MERCHANT = new ActiveMerchantSnapshot(
            41L,
            "mrc_payment_owner"
    );

    @Mock
    private PaymentMerchantResolver merchantResolver;

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentIntentPublicIdGenerator publicIdGenerator;

    private CreatePaymentIntentService service;

    @BeforeEach
    void setUp() {
        service = new CreatePaymentIntentService(
                merchantResolver,
                paymentIntentRepository,
                publicIdGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldCreateAndPersistInitialPaymentForAuthenticatedMerchant() {
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);
        when(publicIdGenerator.nextId()).thenReturn("pi_created");
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CreatePaymentIntentResult result = service.create(command(25_000L, " vnd "));

        ArgumentCaptor<PaymentIntent> persisted = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(persisted.capture());
        PaymentIntent payment = persisted.getValue();
        assertThat(payment.publicId()).isEqualTo("pi_created");
        assertThat(payment.merchantId()).isEqualTo(MERCHANT.internalId());
        assertThat(payment.merchantOrderId()).isEqualTo("ORDER-1001");
        assertThat(payment.description()).isEqualTo("Payment for ORDER-1001");
        assertThat(payment.amount().amountMinor()).isEqualTo(25_000L);
        assertThat(payment.amount().currency().getCurrencyCode()).isEqualTo("VND");
        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.refundedAmount().amountMinor()).isZero();
        assertThat(payment.refundReservedAmount().amountMinor()).isZero();
        assertThat(payment.createdAt()).isEqualTo(NOW);

        assertThat(result).isEqualTo(new CreatePaymentIntentResult(
                "pi_created",
                "ORDER-1001",
                25_000L,
                "VND",
                PaymentStatus.CREATED,
                0L,
                0L,
                0L,
                "Payment for ORDER-1001",
                NOW
        ));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositiveAmountBeforeGeneratingIdOrPersisting(long amountMinor) {
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);

        assertThatThrownBy(() -> service.create(command(amountMinor, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");

        verifyNoInteractions(publicIdGenerator, paymentIntentRepository);
    }

    @Test
    void shouldRejectInvalidCurrencyBeforeGeneratingIdOrPersisting() {
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);

        assertThatThrownBy(() -> service.create(command(1_000L, "not-a-currency")))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(publicIdGenerator, paymentIntentRepository);
    }

    @Test
    void shouldRejectInactiveMerchantBeforeCreatingPayment() {
        ApiException inactive = new ApiException(
                HttpStatus.FORBIDDEN,
                ErrorCode.MERCHANT_SUSPENDED,
                "The merchant is not active."
        );
        when(merchantResolver.resolve(PRINCIPAL)).thenThrow(inactive);

        assertThatThrownBy(() -> service.create(command(1_000L, "USD")))
                .isSameAs(inactive);

        verifyNoInteractions(publicIdGenerator, paymentIntentRepository);
    }

    @Test
    void resultMustNotExposeInternalIdentifiers() {
        assertThat(Arrays.stream(CreatePaymentIntentResult.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.equals("internalid") || name.equals("merchantid"));
    }

    @Test
    void commandMustNotAcceptClientControlledMerchantId() {
        assertThat(Arrays.stream(CreatePaymentIntentCommand.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.equals("merchantid") || name.equals("merchantpublicid"));
    }

    private static CreatePaymentIntentCommand command(long amountMinor, String currency) {
        return new CreatePaymentIntentCommand(
                PRINCIPAL,
                amountMinor,
                currency,
                " ORDER-1001 ",
                " Payment for ORDER-1001 "
        );
    }
}
