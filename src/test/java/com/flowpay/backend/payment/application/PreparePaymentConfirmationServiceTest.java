package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentProcessingEventV1;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
class PreparePaymentConfirmationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-30T09:00:00Z");
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_prepare_owner",
            "key_prepare_payment"
    );

    @Mock
    private PaymentOwnershipService ownershipService;

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private PaymentTransactionPublicIdGenerator transactionPublicIdGenerator;

    @Mock
    private PaymentProviderSelection providerSelection;

    @Mock
    private PaymentIntegrationEventPublisher eventPublisher;

    private PreparePaymentConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new PreparePaymentConfirmationService(
                ownershipService,
                paymentIntentRepository,
                paymentTransactionRepository,
                transactionPublicIdGenerator,
                providerSelection,
                eventPublisher,
                Clock.fixed(STARTED_AT.plusNanos(789), ZoneOffset.UTC)
        );
    }

    @Test
    void shouldPrepareCreatedPaymentAndReturnImmutableProviderCallData() {
        PaymentIntent payment = payment(PaymentStatus.CREATED);
        when(ownershipService.requireOwnedPayment(PRINCIPAL, "pi_prepare"))
                .thenReturn(payment);
        when(transactionPublicIdGenerator.nextId()).thenReturn("ptxn_prepare");
        when(providerSelection.provider()).thenReturn("SIMULATOR");
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PreparedPaymentConfirmation result = service.prepare(command());

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.updatedAt()).isEqualTo(STARTED_AT);
        verify(paymentIntentRepository).save(payment);

        ArgumentCaptor<PaymentTransaction> transactionCaptor =
                ArgumentCaptor.forClass(PaymentTransaction.class);
        verify(paymentTransactionRepository).save(transactionCaptor.capture());
        PaymentTransaction transaction = transactionCaptor.getValue();
        assertThat(transaction.publicId()).isEqualTo("ptxn_prepare");
        assertThat(transaction.paymentIntentId()).isEqualTo(81L);
        assertThat(transaction.attemptNo()).isEqualTo(1);
        assertThat(transaction.provider()).isEqualTo("SIMULATOR");
        assertThat(transaction.status()).isEqualTo(PaymentTransactionStatus.PROCESSING);
        assertThat(transaction.startedAt()).isEqualTo(STARTED_AT);
        assertThat(transaction.completedAt()).isNull();
        verify(eventPublisher).publish(new PaymentProcessingEventV1(
                payment.merchantId(), payment.publicId(), payment.amount().amountMinor(),
                payment.amount().currency().getCurrencyCode(), STARTED_AT));

        assertThat(result).isEqualTo(new PreparedPaymentConfirmation(
                "pi_prepare",
                "ptxn_prepare",
                50_000L,
                "VND",
                "SIMULATOR"
        ));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"PROCESSING", "SUCCEEDED", "FAILED"}
    )
    void shouldRejectPaymentThatIsNotCreated(PaymentStatus status) {
        when(ownershipService.requireOwnedPayment(PRINCIPAL, "pi_prepare"))
                .thenReturn(payment(status));

        assertInvalidState(() -> service.prepare(command()));

        verifyNoInteractions(
                paymentIntentRepository,
                paymentTransactionRepository,
                transactionPublicIdGenerator,
                providerSelection
        );
    }

    @Test
    void shouldTranslateOptimisticLockConflict() {
        arrangeCreatedPayment();
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenThrow(new OptimisticLockingFailureException("stale payment"));

        assertInvalidState(() -> service.prepare(command()));

        verifyNoInteractions(paymentTransactionRepository);
    }

    @Test
    void shouldTranslateDuplicateAttemptConflict() {
        arrangeCreatedPayment();
        when(paymentIntentRepository.save(any(PaymentIntent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentTransactionRepository.save(any(PaymentTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate attempt"));

        assertInvalidState(() -> service.prepare(command()));
    }

    @Test
    void shouldNotDependOnOrCallPaymentProviderDuringPreparation() {
        assertThat(Arrays.stream(PreparePaymentConfirmationService.class.getDeclaredFields())
                .map(field -> field.getType()))
                .noneMatch(PaymentProviderPort.class::equals);
    }

    @Test
    void resultMustNotExposeMutableAggregatesOrInternalIdentifiers() {
        assertThat(Arrays.stream(PreparedPaymentConfirmation.class.getRecordComponents())
                .map(component -> component.getType()))
                .noneMatch(type -> type.equals(PaymentIntent.class)
                        || type.equals(PaymentTransaction.class));
        assertThat(Arrays.stream(PreparedPaymentConfirmation.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("internalid") || name.equals("merchantid"));
    }

    private void arrangeCreatedPayment() {
        when(ownershipService.requireOwnedPayment(PRINCIPAL, "pi_prepare"))
                .thenReturn(payment(PaymentStatus.CREATED));
        when(transactionPublicIdGenerator.nextId()).thenReturn("ptxn_prepare");
        when(providerSelection.provider()).thenReturn("SIMULATOR");
    }

    private static PreparePaymentConfirmationCommand command() {
        return new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_prepare");
    }

    private static PaymentIntent payment(PaymentStatus status) {
        return PaymentIntent.rehydrate(
                81L,
                "pi_prepare",
                41L,
                "ORDER-PREPARE",
                "Prepare payment",
                Money.of(50_000L, "VND"),
                status,
                Money.of(0L, "VND"),
                Money.of(0L, "VND"),
                0L,
                CREATED_AT,
                CREATED_AT
        );
    }

    private static void assertInvalidState(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE);
                    assertThat(exception.getMessage()).isEqualTo(
                            "The payment intent cannot be confirmed from its current state."
                    );
                });
    }
}
