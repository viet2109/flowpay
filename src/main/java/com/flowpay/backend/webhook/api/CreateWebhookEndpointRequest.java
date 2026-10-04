package com.flowpay.backend.webhook.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateWebhookEndpointRequest(
        @NotBlank @Size(max = 2048) String url,
        @NotEmpty @Size(max = 6) List<@NotBlank String> events
) {
}
