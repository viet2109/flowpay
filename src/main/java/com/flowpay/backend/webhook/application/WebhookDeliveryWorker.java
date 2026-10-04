package com.flowpay.backend.webhook.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;

/** No transaction here: each delivery is claimed immediately before its single HTTP invocation. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryWorker {
    private final WebhookDeliveryExecutionService execution;
    private final WebhookHttpClientPort http;
    private final WebhookSecretCipher cipher;
    private final WebhookDeliveryWorkerProperties properties;
    private final Clock clock;

    public void deliverBatch() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Webhook worker must run outside a database transaction");
        }
        if (!properties.enabled()) return;
        for (var expired : execution.expiredCandidates()) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                execution.recoverExpired(expired.internalId(), expired.webhookEndpointId(), expired.attemptCount());
            } catch (RuntimeException exception) {
                log.warn("Webhook delivery {} lease recovery could not be persisted", expired.publicId());
            }
        }
        for (var candidate : execution.candidates()) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                var claim = execution.claim(candidate.internalId(), candidate.webhookEndpointId());
                if (claim.isPresent()) execution.finalizeResult(claim.get(), send(claim.get()));
            } catch (RuntimeException exception) {
                // A failed persistence step leaves a recoverable lease; never resend inline or log exception text.
                log.warn("Webhook delivery {} could not be persisted; later recovery may be required", candidate.publicId());
            }
        }
    }

    private WebhookHttpDeliveryResult send(ClaimedWebhookDelivery claim) {
        long start = System.nanoTime();
        try {
            return Objects.requireNonNull(http.send(new WebhookHttpDeliveryRequest(claim.url(), claim.eventPublicId(),
                    claim.payload().getBytes(StandardCharsets.UTF_8), cipher.decrypt(claim.secretCiphertext()),
                    clock.instant().getEpochSecond())));
        } catch (RuntimeException exception) {
            // Includes a corrupt encrypted secret or an unexpected adapter exception; no sensitive diagnostics survive.
            return WebhookHttpDeliveryResult.failed(WebhookHttpDeliveryResult.Failure.TRANSPORT_FAILURE,
                    Math.max(0, (System.nanoTime() - start) / 1_000_000));
        }
    }
}
