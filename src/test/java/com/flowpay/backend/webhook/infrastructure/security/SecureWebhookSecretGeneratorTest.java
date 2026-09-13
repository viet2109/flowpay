package com.flowpay.backend.webhook.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SecureWebhookSecretGeneratorTest {

    @Test
    void shouldGenerateUniqueUrlSafeSecretsWithAtLeast256RandomBits() {
        SecureWebhookSecretGenerator generator = new SecureWebhookSecretGenerator();

        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).startsWith("whsec_").isNotEqualTo(second);
        assertThat(second).startsWith("whsec_");
        assertThat(Base64.getUrlDecoder().decode(first.substring("whsec_".length())))
                .hasSize(32);
        assertThat(Base64.getUrlDecoder().decode(second.substring("whsec_".length())))
                .hasSize(32);
    }
}
