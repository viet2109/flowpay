package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookDeliveryStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import com.flowpay.backend.webhook.domain.WebhookResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryQueryServiceTest {
    @Mock private MerchantAccessApi merchants;
    @Mock private WebhookDeliveryQueryRepository repository;
    private WebhookDeliveryQueryService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDeliveryQueryService(merchants, repository);
    }

    @Test
    void resolvesMerchantAndParsesPublicFilterNamesBeforeQuerying() {
        when(merchants.requireActiveMerchant("mrc_owner")).thenReturn(new ActiveMerchantSnapshot(1L, "mrc_owner"));
        var page = new WebhookDeliveryViewPage(List.of(), 2, 10, 0, 0, false, true);
        when(repository.findByMerchantId(1L, WebhookDeliveryStatus.DEAD, "wep_owner", WebhookEventType.REFUND_FAILED, 2, 10))
                .thenReturn(page);
        assertThat(service.list(new ListWebhookDeliveriesQuery("mrc_owner", "DEAD", "wep_owner", "refund.failed", 2, 10)))
                .isEqualTo(page);
    }

    @Test
    void invalidQueryDoesNotReachMerchantOrPersistencePorts() {
        for (var query : List.of(new ListWebhookDeliveriesQuery("mrc_owner", null, null, null, -1, 20),
                new ListWebhookDeliveriesQuery("mrc_owner", "bad", null, null, 0, 20),
                new ListWebhookDeliveriesQuery("mrc_owner", null, null, "bad", 0, 20),
                new ListWebhookDeliveriesQuery("mrc_owner", null, "wep_", null, 0, 20))) {
            assertThatThrownBy(() -> service.list(query)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
        verifyNoInteractions(merchants, repository);
    }

    @Test
    void detailUsesOnlyTheOwnedDeliveryIdToLoadHistory() {
        when(merchants.requireActiveMerchant("mrc_owner")).thenReturn(new ActiveMerchantSnapshot(1L, "mrc_owner"));
        var view = new WebhookDeliveryView(3L, 2L, "wdl_owner", "wep_owner", "evt_owner", "payment.failed",
                WebhookResourceType.PAYMENT_INTENT, "pi_owner", WebhookDeliveryStatus.DEAD, 1,
                null, null, 503, "HTTP_STATUS", Instant.EPOCH, Instant.EPOCH);
        var history = List.of(new WebhookDeliveryAttemptView(1, Instant.EPOCH, Instant.EPOCH, 503, 0, "HTTP_STATUS"));
        when(repository.findByPublicIdAndMerchantId("wdl_owner", 1L)).thenReturn(Optional.of(view));
        when(repository.findAttemptsByDeliveryId(3L)).thenReturn(history);
        assertThat(service.get("mrc_owner", "wdl_owner")).isEqualTo(new WebhookDeliveryDetail(view, history));
    }

    @Test
    void notFoundDoesNotLoadAnyAttemptHistory() {
        when(merchants.requireActiveMerchant("mrc_owner")).thenReturn(new ActiveMerchantSnapshot(1L, "mrc_owner"));
        when(repository.findByPublicIdAndMerchantId("wdl_other", 1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("mrc_owner", "wdl_other")).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ErrorCode.WEBHOOK_DELIVERY_NOT_FOUND));
        verify(repository, never()).findAttemptsByDeliveryId(org.mockito.ArgumentMatchers.anyLong());
    }
}
