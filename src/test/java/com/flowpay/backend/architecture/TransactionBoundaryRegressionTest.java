package com.flowpay.backend.architecture;

import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.infrastructure.messaging.outbox.IntegrationEventEnvelope;
import com.flowpay.backend.infrastructure.messaging.outbox.TransactionalOutboxEventPublisher;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayPersistenceService;
import com.flowpay.backend.infrastructure.messaging.outbox.relay.OutboxRelayService;
import com.flowpay.backend.infrastructure.messaging.rabbit.ConfirmedRabbitIntegrationEventPublisher;
import com.flowpay.backend.infrastructure.messaging.rabbit.LedgerIntegrationEventHandler;
import com.flowpay.backend.ledger.application.LedgerPostingService;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import com.flowpay.backend.ledger.application.LedgerReversalService;
import com.flowpay.backend.ledger.application.ReverseLedgerTransactionCommand;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionBoundaryRegressionTest {

    @Test
    void webhookDeliveryKeepsHttpBetweenIndependentClaimAndFinalizationTransactions() throws Exception {
        var execution = com.flowpay.backend.webhook.application.WebhookDeliveryExecutionService.class;
        assertNotTransactional(com.flowpay.backend.webhook.application.WebhookDeliveryWorker.class, "deliverBatch");
        for (Method method : execution.getDeclaredMethods()) {
            if (method.getName().equals("claim") || method.getName().equals("finalizeResult") || method.getName().equals("candidates")) {
                assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
            }
        }
    }

    @Test
    void webhookHttpAdapterMustNotOwnADatabaseTransaction() throws Exception {
        assertNotTransactional(com.flowpay.backend.webhook.infrastructure.http.JdkWebhookHttpClientAdapter.class,
                "send", com.flowpay.backend.webhook.application.WebhookHttpDeliveryRequest.class);
    }

    @Test
    void webhookMaterializationMustCommitBeforeListenerAcknowledgement() throws Exception {
        assertTransactional(com.flowpay.backend.webhook.application.WebhookEventMaterializationService.class,
                "materialize", com.flowpay.backend.webhook.application.MaterializeWebhookEventCommand.class);
        assertNotTransactional(com.flowpay.backend.infrastructure.messaging.rabbit.WebhookIntegrationEventListener.class,
                "consume", org.springframework.amqp.core.Message.class);
    }

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

    @Test
    void ledgerReversalMustOwnItsTransactionBoundary() throws Exception {
        assertTransactional(
                LedgerReversalService.class,
                "reverse",
                ReverseLedgerTransactionCommand.class
        );
    }

    @Test
    void outboxRelayMustKeepBrokerIoBetweenShortDatabaseTransactions()
            throws Exception {
        assertNotTransactional(
                OutboxRelayService.class,
                "relayDueEvents"
        );
        assertTransactional(
                OutboxRelayPersistenceService.class,
                "findDue",
                java.time.Instant.class,
                int.class
        );
        assertTransactional(
                OutboxRelayPersistenceService.class,
                "markPublished",
                String.class,
                java.time.Instant.class
        );
        assertTransactional(
                OutboxRelayPersistenceService.class,
                "recordFailure",
                String.class,
                java.time.Instant.class,
                String.class
        );
        assertMandatory(TransactionalOutboxEventPublisher.class);
        assertNotTransactional(
                ConfirmedRabbitIntegrationEventPublisher.class,
                "publish",
                IntegrationEventEnvelope.class
        );
    }

    @Test
    void ledgerConsumerMustOwnTransactionWhilePostingApiRemainsMandatory()
            throws Exception {
        assertTransactional(
                LedgerIntegrationEventHandler.class,
                "handlePayment",
                PostPaymentSucceededCommand.class
        );
        assertTransactional(
                LedgerIntegrationEventHandler.class,
                "handleRefund",
                PostRefundSucceededCommand.class
        );
        assertMandatory(
                LedgerPostingService.class,
                "postPaymentSucceeded",
                PostPaymentSucceededCommand.class
        );
        assertMandatory(
                LedgerPostingService.class,
                "postRefundSucceeded",
                PostRefundSucceededCommand.class
        );
    }

    private static void assertTransactional(
            Class<?> type,
            String methodName,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameterTypes);
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

    private static void assertMandatory(
            Class<?> type,
            String methodName,
            Class<?> parameterType
    ) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName, parameterType);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .as("%s.%s propagation", type.getSimpleName(), methodName)
                .isEqualTo(Propagation.MANDATORY);
    }

    private static void assertMandatory(Class<?> type) {
        assertThat(type.getAnnotation(Transactional.class).propagation())
                .as("%s propagation", type.getSimpleName())
                .isEqualTo(Propagation.MANDATORY);
    }

    private static void assertNotTransactional(
            Class<?> type,
            String methodName
    ) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(methodName);
        assertThat(type.getAnnotation(Transactional.class))
                .as("%s class-level transaction", type.getSimpleName())
                .isNull();
        assertThat(method.getAnnotation(Transactional.class))
                .as("%s.%s outer transaction", type.getSimpleName(), methodName)
                .isNull();
    }
}
