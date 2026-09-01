package com.flowpay.backend.architecture;

import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.payment.application.ConfirmPaymentCommand;
import com.flowpay.backend.payment.application.ConfirmPaymentService;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.FinalizePaymentConfirmationService;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentCommand;
import com.flowpay.backend.payment.application.IdempotentConfirmPaymentService;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationCommand;
import com.flowpay.backend.payment.application.PreparePaymentConfirmationService;
import com.flowpay.backend.refund.application.FinalizeRefundCommand;
import com.flowpay.backend.refund.application.FinalizeRefundService;
import com.flowpay.backend.refund.application.IdempotentRefundCommand;
import com.flowpay.backend.refund.application.IdempotentRefundService;
import com.flowpay.backend.refund.application.PrepareRefundCommand;
import com.flowpay.backend.refund.application.PrepareRefundService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionBoundaryRegressionTest {

    @Test
    void paymentConfirmationMustKeepProviderBetweenTwoShortTransactions()
            throws Exception {
        assertNotTransactional(
                IdempotentConfirmPaymentService.class,
                "confirm",
                IdempotentConfirmPaymentCommand.class
        );
        assertNotTransactional(
                ConfirmPaymentService.class,
                "confirm",
                ConfirmPaymentCommand.class
        );
        assertTransactional(
                PreparePaymentConfirmationService.class,
                "prepare",
                PreparePaymentConfirmationCommand.class
        );
        assertTransactional(
                FinalizePaymentConfirmationService.class,
                "finalizeConfirmation",
                FinalizePaymentConfirmationCommand.class
        );
        assertTransactional(
                IdempotencyCompletionService.class,
                "complete",
                IdempotencyCompletionCommand.class
        );
    }

    @Test
    void refundMustKeepProviderOutsidePrepareFinalizeAndCompletionTransactions()
            throws Exception {
        assertNotTransactional(
                IdempotentRefundService.class,
                "create",
                IdempotentRefundCommand.class
        );
        assertTransactional(
                PrepareRefundService.class,
                "prepare",
                PrepareRefundCommand.class
        );
        assertTransactional(
                FinalizeRefundService.class,
                "finalizeRefund",
                FinalizeRefundCommand.class
        );
        assertTransactional(
                IdempotencyCompletionService.class,
                "complete",
                IdempotencyCompletionCommand.class
        );
    }

    private static void assertTransactional(
            Class<?> type,
            String methodName,
            Class<?> parameterType
    ) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameterType);
        assertThat(method.getAnnotation(Transactional.class))
                .as("%s.%s transaction boundary", type.getSimpleName(), methodName)
                .isNotNull();
    }

    private static void assertNotTransactional(
            Class<?> type,
            String methodName,
            Class<?> parameterType
    ) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameterType);
        assertThat(type.getAnnotation(Transactional.class))
                .as("%s class-level transaction", type.getSimpleName())
                .isNull();
        assertThat(method.getAnnotation(Transactional.class))
                .as("%s.%s outer transaction", type.getSimpleName(), methodName)
                .isNull();
    }
}
