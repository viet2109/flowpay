package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class FinalizePaymentConfirmationService {

    private static final String INVALID_STATE_DETAIL =
            "The payment confirmation cannot be finalized from its current state.";
    private static final String NOT_FOUND_DETAIL =
            "The payment confirmation was not found.";

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentIntegrationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public FinalizedPaymentConfirmation finalizeConfirmation(
            FinalizePaymentConfirmationCommand command
    ) {
        PaymentIntent payment = paymentIntentRepository
                .findByPublicId(command.paymentPublicId())
                .orElseThrow(FinalizePaymentConfirmationService::notFound);
        PaymentTransaction transaction = paymentTransactionRepository
                .findByPublicId(command.transactionPublicId())
                .orElseThrow(FinalizePaymentConfirmationService::notFound);

        PaymentProviderResult providerResult = command.providerResult();
        validateConfirmation(payment, transaction, providerResult);
        Instant completedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        applyOutcome(payment, transaction, providerResult, completedAt);

        PaymentIntent savedPayment;
        PaymentTransaction savedTransaction;
        try {
            savedPayment = paymentIntentRepository.save(payment);
            savedTransaction = paymentTransactionRepository.save(transaction);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw invalidState();
        }

        if (providerResult.outcome() == ProviderOutcome.SUCCESS) {
            eventPublisher.publish(new PaymentSucceededEventV1(
                    savedPayment.merchantId(),
                    savedPayment.publicId(),
                    savedPayment.amount().amountMinor(),
                    savedPayment.amount().currency().getCurrencyCode(),
                    completedAt
            ));
        }

        return new FinalizedPaymentConfirmation(
                savedPayment.publicId(),
                savedPayment.status(),
                savedTransaction.publicId(),
                savedTransaction.status(),
                savedTransaction.provider(),
                savedTransaction.providerTransactionId(),
                savedTransaction.failureCode(),
                savedTransaction.failureMessage()
        );
    }

    private static void validateConfirmation(
            PaymentIntent payment,
            PaymentTransaction transaction,
            PaymentProviderResult providerResult
    ) {
        if (!transaction.paymentIntentId().equals(payment.internalId())) {
            throw notFound();
        }
        if (payment.status() != PaymentStatus.PROCESSING
                || transaction.status() != PaymentTransactionStatus.PROCESSING
                || !transaction.provider().equals(providerResult.provider())) {
            throw invalidState();
        }
    }

    private static void applyOutcome(
            PaymentIntent payment,
            PaymentTransaction transaction,
            PaymentProviderResult providerResult,
            Instant completedAt
    ) {
        ProviderOutcome outcome = providerResult.outcome();
        switch (outcome) {
            case SUCCESS -> {
                transaction.markSucceeded(
                        providerResult.providerTransactionId(),
                        completedAt
                );
                payment.markSucceeded(completedAt);
            }
            case DECLINED, TECHNICAL_FAILURE -> {
                transaction.markFailed(
                        providerResult.providerTransactionId(),
                        providerResult.failureCode(),
                        providerResult.failureMessage(),
                        completedAt
                );
                payment.markFailed(completedAt);
            }
            case UNKNOWN -> transaction.markUnknown(
                    providerResult.providerTransactionId(),
                    providerResult.failureCode(),
                    providerResult.failureMessage(),
                    completedAt
            );
        }
    }

    private static ApiException notFound() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                ErrorCode.PAYMENT_NOT_FOUND,
                NOT_FOUND_DETAIL
        );
    }

    private static ApiException invalidState() {
        return new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.PAYMENT_INVALID_STATE,
                INVALID_STATE_DETAIL
        );
    }
}
