package com.flowpay.backend.webhook.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEventTypeTest {

    @Test
    void shouldExposeExactlyTheSixCanonicalPublicEventNames() {
        assertThat(Arrays.stream(WebhookEventType.values()).map(WebhookEventType::value))
                .containsExactly(
                        "payment.processing",
                        "payment.succeeded",
                        "payment.failed",
                        "refund.processing",
                        "refund.succeeded",
                        "refund.failed"
                );
    }

    @Test
    void shouldParseOnlyExactCanonicalValues() {
        for (WebhookEventType eventType : WebhookEventType.values()) {
            assertThat(WebhookEventType.fromValue(eventType.value())).isEqualTo(eventType);
        }

        assertThatThrownBy(() -> WebhookEventType.fromValue("PAYMENT.SUCCEEDED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEventType.fromValue(" payment.succeeded "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEventType.fromValue("payment.cancelled"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEventType.fromValue(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEventType.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
