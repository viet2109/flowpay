package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PreparePaymentConfirmationService {

    private static final int FIRST_ATTEMPT = 1;
    private static final String INVALID_STATE_DETAIL =
            "The payment intent cannot be confirmed from its current state.";

    private final PaymentOwnershipService ownershipService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final PaymentTransactionPublicIdGenerator transactionPublicIdGenerator;
    private final PaymentProviderSelection providerSelection;
    private final Clock clock;

    @Transactional
    public PreparedPaymentConfirmation prepare(PreparePaymentConfirmationCommand command) {
        PaymentIntent payment = ownershipService.requireOwnedPayment(
                command.merchantContext(),
                command.paymentPublicId()
        );
        Instant startedAt = clock.instant();
        transitionToProcessing(payment, startedAt);

        PaymentTransaction transaction = PaymentTransaction.createProcessing(
                transactionPublicIdGenerator.nextId(),
                payment.internalId(),
                FIRST_ATTEMPT,
                providerSelection.provider(),
                startedAt
        );

        try {
            PaymentIntent savedPayment = paymentIntentRepository.save(payment);
            PaymentTransaction savedTransaction = paymentTransactionRepository.save(transaction);
            return toResult(savedPayment, savedTransaction);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw invalidState();
        }
    }

    private static void transitionToProcessing(PaymentIntent payment, Instant startedAt) {
        try {
            payment.startProcessing(startedAt);
        } catch (IllegalStateException exception) {
            throw invalidState();
        }
    }

    private static PreparedPaymentConfirmation toResult(
            PaymentIntent payment,
            PaymentTransaction transaction
    ) {
        return new PreparedPaymentConfirmation(
                payment.publicId(),
                transaction.publicId(),
                payment.amount().amountMinor(),
                payment.amount().currency().getCurrencyCode(),
                transaction.provider()
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
