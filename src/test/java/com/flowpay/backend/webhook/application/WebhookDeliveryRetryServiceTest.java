package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookDelivery;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryRetryServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    @Mock private MerchantAccessApi merchantAccess;
    @Mock private WebhookDeliveryQueryRepository queries;
    @Mock private WebhookEndpointRepository endpoints;
    @Mock private WebhookDeliveryRepository deliveries;
    private WebhookDeliveryRetryService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDeliveryRetryService(merchantAccess, queries, endpoints, deliveries,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(merchantAccess.requireActiveMerchant("mrc_owner")).thenReturn(new ActiveMerchantSnapshot(1L, "mrc_owner"));
    }

    @Test
    void locksEndpointBeforeDeliveryAndSchedulesOnlyThroughDomainBehavior() {
        WebhookDelivery current = delivery(WebhookDeliveryStatus.DEAD, NOW.minusSeconds(1));
        ownedAndLocked(current, WebhookEndpointStatus.ACTIVE);
        assertThat(service.retry("mrc_owner", "wdl_owner")).isEqualTo(view());
        var order = inOrder(endpoints, deliveries);
        order.verify(endpoints).findByInternalIdForShare(2L, false);
        order.verify(deliveries).findByInternalIdForUpdate(3L);
        order.verify(deliveries).save(current);
        assertThat(current.status()).isEqualTo(WebhookDeliveryStatus.RETRYING);
        assertThat(current.nextAttemptAt()).isEqualTo(NOW);
        assertThat(current.attemptCount()).isEqualTo(6);
        assertThat(current.lastHttpStatus()).isEqualTo(503);
        assertThat(current.lastError()).isEqualTo("HTTP_STATUS");
    }

    @Test
    void rechecksLockedStateRatherThanTrustingTheDeadReadProjection() {
        ownedAndLocked(delivery(WebhookDeliveryStatus.RETRYING, NOW), WebhookEndpointStatus.ACTIVE);
        assertInvalidState();
        verify(deliveries, never()).save(any());
    }

    @Test
    void disabledEndpointRejectsRetryWithoutSaving() {
        ownedAndLocked(delivery(WebhookDeliveryStatus.DEAD, NOW), WebhookEndpointStatus.DISABLED);
        assertInvalidState();
        verify(deliveries, never()).save(any());
    }

    @Test
    void staleConcurrentRetryCannotRescheduleANewerDeadAttemptAfterWorkerHasAlreadyFailedAgain() {
        WebhookDelivery newer = WebhookDelivery.rehydrate(3L, "wdl_owner", 4L, 2L,
                WebhookDeliveryStatus.DEAD, 7, null, null, null, 503, "HTTP_STATUS",
                NOW.minusSeconds(10), NOW, 3);
        ownedAndLocked(newer, WebhookEndpointStatus.ACTIVE);
        assertInvalidState();
        verify(deliveries, never()).save(any());
    }

    @Test
    void unknownOrUnownedDeliveryIsNotFoundBeforeAcquiringAnyLocks() {
        when(queries.findByPublicIdAndMerchantId("wdl_owner", 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retry("mrc_owner", "wdl_owner"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.WEBHOOK_DELIVERY_NOT_FOUND));
        verifyNoInteractions(endpoints, deliveries);
    }

    @Test
    void backwardClockDoesNotMoveTheDomainTimestampBackwards() {
        WebhookDelivery current = delivery(WebhookDeliveryStatus.DEAD, NOW.plusSeconds(1));
        ownedAndLocked(current, WebhookEndpointStatus.ACTIVE);
        service.retry("mrc_owner", "wdl_owner");
        assertThat(current.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    }

    private void assertInvalidState() {
        assertThatThrownBy(() -> service.retry("mrc_owner", "wdl_owner"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(ErrorCode.WEBHOOK_INVALID_STATE));
    }

    private void ownedAndLocked(WebhookDelivery delivery, WebhookEndpointStatus status) {
        when(queries.findByPublicIdAndMerchantId("wdl_owner", 1L)).thenReturn(Optional.of(view()));
        when(endpoints.findByInternalIdForShare(2L, false)).thenReturn(Optional.of(WebhookEndpoint.rehydrate(
                2L, "wep_owner", 1L, "https://example.com/hook", "ciphertext", status,
                List.of(WebhookEventType.PAYMENT_FAILED), 0L, NOW.minusSeconds(10), NOW)));
        when(deliveries.findByInternalIdForUpdate(3L)).thenReturn(Optional.of(delivery));
    }

    private static WebhookDelivery delivery(WebhookDeliveryStatus status, Instant updatedAt) {
        return WebhookDelivery.rehydrate(3L, "wdl_owner", 4L, 2L, status, 6,
                status == WebhookDeliveryStatus.RETRYING ? updatedAt : null, null, null, 503,
                "HTTP_STATUS", NOW.minusSeconds(10), updatedAt, 0);
    }

    private static WebhookDeliveryView view() {
        return new WebhookDeliveryView(3L, 2L, "wdl_owner", "wep_owner", "evt_owner", "payment.failed",
                WebhookResourceType.PAYMENT_INTENT, "pi_owner", WebhookDeliveryStatus.DEAD, 6,
                null, null, 503, "HTTP_STATUS", NOW.minusSeconds(10), NOW);
    }
}
