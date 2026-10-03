package com.flowpay.backend.webhook.application;

import com.flowpay.backend.webhook.domain.WebhookDelivery;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookDeliveryWorkerTest {
    private final WebhookDeliveryExecutionService execution = mock(WebhookDeliveryExecutionService.class);
    private final WebhookHttpClientPort http = mock(WebhookHttpClientPort.class);
    private final WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledWorkerDoesNotEvenQueryCandidates() {
        worker(false).deliverBatch();
        verifyNoInteractions(execution, http, cipher);
    }

    @Test
    void lostClaimDoesNotDecryptOrSend() {
        var candidate = candidate(1);
        when(execution.candidates()).thenReturn(List.of(candidate));
        when(execution.claim(1, 10)).thenReturn(Optional.empty());
        worker(true).deliverBatch();
        verifyNoInteractions(http, cipher);
        verify(execution, never()).finalizeResult(any(), any());
    }

    @Test
    void cannotDecryptSecretCompletesFailureWithoutSendingOrLeakingDiagnostic() {
        var claim = setupClaim(1);
        when(cipher.decrypt(anyString())).thenThrow(new IllegalArgumentException("ciphertext: private"));
        worker(true).deliverBatch();
        verifyNoInteractions(http);
        verify(execution).finalizeResult(eq(claim), argThat(result -> result.httpStatus() == null
                && result.errorMessage().equals("TRANSPORT_FAILURE")));
    }

    @Test
    void persistenceFailureAfterHttpDoesNotResendAndUnrelatedDeliveryContinues() {
        var first = setupClaim(1);
        var second = snapshot(2);
        var candidates = List.of(candidate(1), candidate(2));
        when(execution.candidates()).thenReturn(candidates);
        when(execution.claim(2, 20)).thenReturn(Optional.of(second));
        when(cipher.decrypt(anyString())).thenReturn("whsec_test");
        when(http.send(any())).thenReturn(WebhookHttpDeliveryResult.http(200, 2));
        doThrow(new IllegalStateException("SQL details")).when(execution).finalizeResult(eq(first), any());
        worker(true).deliverBatch();
        verify(http, times(2)).send(any());
        verify(execution).claim(1, 10);
        verify(execution).claim(2, 20);
        verify(execution).finalizeResult(eq(second), any());
    }

    private ClaimedWebhookDelivery setupClaim(long id) {
        var claim = snapshot(id);
        var candidate = candidate(id);
        when(execution.candidates()).thenReturn(List.of(candidate));
        when(execution.claim(id, id * 10)).thenReturn(Optional.of(claim));
        return claim;
    }

    private WebhookDelivery candidate(long id) {
        var candidate = mock(WebhookDelivery.class);
        when(candidate.internalId()).thenReturn(id);
        when(candidate.webhookEndpointId()).thenReturn(id * 10);
        return candidate;
    }

    private ClaimedWebhookDelivery snapshot(long id) {
        return new ClaimedWebhookDelivery(id, id * 10, 1, "evt_test" + id,
                "https://merchant.example/webhook", "encrypted", "{\"id\":\"evt_test\"}");
    }

    private WebhookDeliveryWorker worker(boolean enabled) {
        return new WebhookDeliveryWorker(execution, http, cipher,
                new WebhookDeliveryWorkerProperties(enabled, Duration.ofSeconds(1), 2, Duration.ofSeconds(30)), clock);
    }
}
