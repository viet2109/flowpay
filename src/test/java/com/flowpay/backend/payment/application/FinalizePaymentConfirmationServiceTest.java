package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.application.event.PaymentFailedEventV1;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinalizePaymentConfirmationServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-08-30T09:00:00Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-30T09:00:05Z");

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @Mock
    private PaymentIntegrationEventPublisher eventPublisher;

    private FinalizePaymentConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new FinalizePaymentConfirmationService(
                paymentIntentRepository,
                paymentTransactionRepository,
                eventPublisher,
                Clock.fixed(COMPLETED_AT, ZoneOffset.UTC)
        );
    }

    @ParameterizedTest
    @MethodSource("providerOutcomes")
    void shouldAtomicallyMapNormalizedProviderOutcome(
            PaymentProviderResult providerResult,
            PaymentStatus expectedPaymentStatus,
            PaymentTransactionStatus expectedTransactionStatus
    ) {
        PaymentIntent payment = processingPayment();
        PaymentTransaction transaction = processingTransaction(payment.internalId());
        arrangeSuccessfulPersistence(payment, transaction);

        FinalizedPaymentConfirmation result = service.finalizeConfirmation(command(providerResult));

        assertThat(payment.status()).isEqualTo(expectedPaymentStatus);
        assertThat(transaction.status()).isEqualTo(expectedTransactionStatus);
        assertThat(transaction.providerTransactionId())
                .isEqualTo(providerResult.providerTransactionId());
        assertThat(transaction.failureCode()).isEqualTo(providerResult.failureCode());
        assertThat(transaction.failureMessage()).isEqualTo(providerResult.failureMessage());
        assertThat(transaction.completedAt()).isEqualTo(COMPLETED_AT);
        if (providerResult.outcome() == ProviderOutcome.UNKNOWN) {
            assertThat(payment.updatedAt()).isEqualTo(STARTED_AT);
        } else {
            assertThat(payment.updatedAt()).isEqualTo(COMPLETED_AT);
        }
        verify(paymentIntentRepository).save(payment);
        verify(paymentTransactionRepository).save(transaction);
        if (providerResult.outcome() == ProviderOutcome.SUCCESS) {
            verify(eventPublisher).publish(
                    new PaymentSucceededEventV1(
                            payment.merchantId(),
                            payment.publicId(),
                            payment.amount().amountMinor(),
                            payment.amount().currency().getCurrencyCode(),
                            COMPLETED_AT
                    )
            );
        } else if (providerResult.outcome() == ProviderOutcome.UNKNOWN) {
            verifyNoInteractions(eventPublisher);
        } else {
            verify(eventPublisher).publish(new PaymentFailedEventV1(
                    payment.merchantId(), payment.publicId(), payment.amount().amountMinor(),
                    payment.amount().currency().getCurrencyCode(),
                    providerResult.failureCode(), providerResult.failureMessage(), COMPLETED_AT));
        }
        assertThat(result).isEqualTo(new FinalizedPaymentConfirmation(
                "pi_finalize",
                expectedPaymentStatus,
                "ptxn_finalize",
                expectedTransactionStatus,
                providerResult.provider(),
                providerResult.providerTransactionId(),
                providerResult.failureCode(),
                providerResult.failureMessage()
        ));
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentTransactionStatus.class,
            names = {"SUCCEEDED", "FAILED", "UNKNOWN"}
    )
    void shouldRejectTerminalTransaction(PaymentTransactionStatus status) {
        PaymentIntent payment = processingPayment();
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPublicId("ptxn_finalize"))
                .thenReturn(Optional.of(terminalTransaction(payment.internalId(), status)));

        assertInvalidState(() -> service.finalizeConfirmation(command(success())));

        verify(paymentIntentRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void shouldHandleMissingPaymentSafely() {
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.empty());

        assertNotFound(() -> service.finalizeConfirmation(command(success())));

        verifyNoInteractions(paymentTransactionRepository);
    }

    @Test
    void shouldHandleMissingTransactionSafely() {
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.of(processingPayment()));
        when(paymentTransactionRepository.findByPublicId("ptxn_finalize"))
                .thenReturn(Optional.empty());

        assertNotFound(() -> service.finalizeConfirmation(command(success())));

        verify(paymentIntentRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void shouldRejectTransactionThatBelongsToAnotherPayment() {
        PaymentIntent payment = processingPayment();
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPublicId("ptxn_finalize"))
                .thenReturn(Optional.of(processingTransaction(999L)));

        assertNotFound(() -> service.finalizeConfirmation(command(success())));

        verify(paymentIntentRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void shouldRejectResultFromAnotherProvider() {
        PaymentIntent payment = processingPayment();
        PaymentTransaction transaction = processingTransaction(payment.internalId());
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPublicId("ptxn_finalize"))
                .thenReturn(Optional.of(transaction));
        PaymentProviderResult otherProvider = new PaymentProviderResult(
                "OTHER",
                ProviderOutcome.SUCCESS,
                "other_txn",
                null,
                null
        );

        assertInvalidState(() -> service.finalizeConfirmation(command(otherProvider)));

        verify(paymentIntentRepository, never()).save(any());
        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void shouldTranslateOptimisticLockConflict() {
        PaymentIntent payment = processingPayment();
        PaymentTransaction transaction = processingTransaction(payment.internalId());
        arrangeLookups(payment, transaction);
        when(paymentIntentRepository.save(payment))
                .thenThrow(new OptimisticLockingFailureException("stale payment"));

        assertInvalidState(() -> service.finalizeConfirmation(command(success())));

        verify(paymentTransactionRepository, never()).save(any());
    }

    @Test
    void shouldTranslatePersistenceConflictAfterPaymentUpdate() {
        PaymentIntent payment = processingPayment();
        PaymentTransaction transaction = processingTransaction(payment.internalId());
        arrangeLookups(payment, transaction);
        when(paymentIntentRepository.save(payment)).thenReturn(payment);
        when(paymentTransactionRepository.save(transaction))
                .thenThrow(new DataIntegrityViolationException("transaction update failed"));

        assertInvalidState(() -> service.finalizeConfirmation(command(success())));
    }

    @Test
    void shouldPropagateOutboxFailureWithoutMisclassifyingItAsPaymentState() {
        PaymentIntent payment = processingPayment();
        PaymentTransaction transaction = processingTransaction(payment.internalId());
        arrangeSuccessfulPersistence(payment, transaction);
        RuntimeException outboxFailure = new RuntimeException("outbox unavailable");
        org.mockito.Mockito.doThrow(outboxFailure)
                .when(eventPublisher)
                .publish(any(PaymentSucceededEventV1.class));

        assertThatThrownBy(() -> service.finalizeConfirmation(command(success())))
                .isSameAs(outboxFailure)
                .isNotInstanceOf(ApiException.class);
        verify(paymentIntentRepository).save(payment);
        verify(paymentTransactionRepository).save(transaction);
    }

    @Test
    void shouldOnlyPersistNormalizedMetadataAndReturnImmutableState() {
        assertThat(Arrays.stream(PaymentProviderResult.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("raw") || name.contains("payload"));
        assertThat(Arrays.stream(PaymentTransaction.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase()))
                .noneMatch(name -> name.contains("raw") || name.contains("payload"));
        assertThat(Arrays.stream(FinalizedPaymentConfirmation.class.getRecordComponents())
                .map(component -> component.getType()))
                .noneMatch(type -> type.equals(PaymentIntent.class)
                        || type.equals(PaymentTransaction.class));
        assertThat(Arrays.stream(FinalizePaymentConfirmationService.class.getDeclaredFields())
                .map(field -> field.getType()))
                .noneMatch(PaymentProviderPort.class::equals);
    }

    private void arrangeSuccessfulPersistence(
            PaymentIntent payment,
            PaymentTransaction transaction
    ) {
        arrangeLookups(payment, transaction);
        when(paymentIntentRepository.save(payment)).thenReturn(payment);
        when(paymentTransactionRepository.save(transaction)).thenReturn(transaction);
    }

    private void arrangeLookups(PaymentIntent payment, PaymentTransaction transaction) {
        when(paymentIntentRepository.findByPublicId("pi_finalize"))
                .thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByPublicId("ptxn_finalize"))
                .thenReturn(Optional.of(transaction));
    }

    private static FinalizePaymentConfirmationCommand command(
            PaymentProviderResult providerResult
    ) {
        return new FinalizePaymentConfirmationCommand(
                "pi_finalize",
                "ptxn_finalize",
                providerResult
        );
    }

    private static PaymentIntent processingPayment() {
        return PaymentIntent.rehydrate(
                81L,
                "pi_finalize",
                41L,
                "ORDER-FINALIZE",
                "Finalize payment",
                Money.of(50_000L, "VND"),
                PaymentStatus.PROCESSING,
                Money.of(0L, "VND"),
                Money.of(0L, "VND"),
                1L,
                CREATED_AT,
                STARTED_AT
        );
    }

    private static PaymentTransaction processingTransaction(long paymentIntentId) {
        return PaymentTransaction.rehydrate(
                91L,
                "ptxn_finalize",
                paymentIntentId,
                1,
                "SIMULATOR",
                null,
                PaymentTransactionStatus.PROCESSING,
                null,
                null,
                STARTED_AT,
                null,
                0L
        );
    }

    private static PaymentTransaction terminalTransaction(
            long paymentIntentId,
            PaymentTransactionStatus status
    ) {
        return PaymentTransaction.rehydrate(
                91L,
                "ptxn_finalize",
                paymentIntentId,
                1,
                "SIMULATOR",
                status == PaymentTransactionStatus.SUCCEEDED ? "sim_terminal" : null,
                status,
                status == PaymentTransactionStatus.SUCCEEDED ? null : "TERMINAL",
                status == PaymentTransactionStatus.SUCCEEDED ? null : "Already terminal.",
                STARTED_AT,
                COMPLETED_AT,
                1L
        );
    }

    private static Stream<Arguments> providerOutcomes() {
        return Stream.of(
                Arguments.of(
                        success(),
                        PaymentStatus.SUCCEEDED,
                        PaymentTransactionStatus.SUCCEEDED
                ),
                Arguments.of(
                        failure(ProviderOutcome.DECLINED, "CARD_DECLINED", "Declined."),
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                ),
                Arguments.of(
                        failure(ProviderOutcome.UNKNOWN, "PROVIDER_TIMEOUT", "Unknown."),
                        PaymentStatus.PROCESSING,
                        PaymentTransactionStatus.UNKNOWN
                ),
                Arguments.of(
                        failure(
                                ProviderOutcome.TECHNICAL_FAILURE,
                                "PROVIDER_UNAVAILABLE",
                                "Unavailable."
                        ),
                        PaymentStatus.FAILED,
                        PaymentTransactionStatus.FAILED
                )
        );
    }

    private static PaymentProviderResult success() {
        return new PaymentProviderResult(
                "SIMULATOR",
                ProviderOutcome.SUCCESS,
                "sim_pi_finalize",
                null,
                null
        );
    }

    private static PaymentProviderResult failure(
            ProviderOutcome outcome,
            String code,
            String message
    ) {
        return new PaymentProviderResult(
                "SIMULATOR",
                outcome,
                outcome == ProviderOutcome.DECLINED ? "sim_pi_finalize" : null,
                code,
                message
        );
    }

    private static void assertInvalidState(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE);
                    assertThat(exception.getMessage()).isEqualTo(
                            "The payment confirmation cannot be finalized from its current state."
                    );
                });
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo(
                            "The payment confirmation was not found."
                    );
                });
    }
}
