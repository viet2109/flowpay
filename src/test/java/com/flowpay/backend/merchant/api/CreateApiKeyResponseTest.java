package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CreateApiKeyResponseTest {

    @Test
    void shouldRedactRawKeyFromTextRepresentation() {
        String rawKey = "fp_test_" + "A".repeat(43);
        CreateApiKeyResponse response = new CreateApiKeyResponse(
                "key_test",
                "Backend",
                rawKey,
                "fp_test_" + "A".repeat(12),
                ApiKeyStatus.ACTIVE,
                Instant.parse("2026-08-30T04:00:00Z")
        );

        assertThat(response.toString()).contains("key=[REDACTED]").doesNotContain(rawKey);
    }
}
