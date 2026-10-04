package com.flowpay.backend.webhook.application;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult.Failure.*;

class WebhookHttpDeliveryContractTest {
    @Test
    void defensivelyCopiesBodyAndRedactsAllSensitiveRequestFields() {
        byte[] bytes = "private-body".getBytes(StandardCharsets.UTF_8);
        var request = new WebhookHttpDeliveryRequest("https://example.com/?token=private-url", "evt_one",
                bytes, "whsec_private", 1700000000);
        bytes[0] = 0;
        byte[] returned = request.body();
        returned[0] = 0;
        assertThat(request.body()).isEqualTo("private-body".getBytes(StandardCharsets.UTF_8));
        assertThat(request.toString()).contains("[REDACTED]")
                .doesNotContain("whsec_private", "private-body", "private-url", "https://");
    }

    @Test
    void rejectsInvalidRequestAndHeaderInjection() {
        assertThatThrownBy(() -> request("evt_one\r\nX-Injected: evil", new byte[]{1}, "secret", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("internal_id", new byte[]{1}, "secret", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("evt_one", new byte[0], "secret", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("evt_one", new byte[]{1}, " ", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request("evt_one", new byte[]{1}, "secret", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultExposesOnlyStatusDurationAndStableDiagnostic() {
        assertThat(WebhookHttpDeliveryResult.http(204, 3).successful()).isTrue();
        assertThat(WebhookHttpDeliveryResult.http(204, 3).errorMessage()).isNull();
        assertThat(WebhookHttpDeliveryResult.http(503, 3).failure()).isEqualTo(HTTP_STATUS);
        assertThat(WebhookHttpDeliveryResult.failed(REQUEST_TIMEOUT, 5).errorMessage()).isEqualTo("REQUEST_TIMEOUT");
        assertThatThrownBy(() -> new WebhookHttpDeliveryResult(200, 0, TLS_FAILURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookHttpDeliveryResult(500, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookHttpDeliveryResult(null, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookHttpDeliveryResult.failed(HTTP_STATUS, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookHttpDeliveryResult.http(600, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookHttpDeliveryResult.http(200, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private WebhookHttpDeliveryRequest request(String id, byte[] body, String secret, long timestamp) {
        return new WebhookHttpDeliveryRequest("https://example.com", id, body, secret, timestamp);
    }
}
