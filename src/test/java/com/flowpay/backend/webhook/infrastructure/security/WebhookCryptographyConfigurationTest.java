package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.webhook.application.WebhookSecretCipher;
import com.flowpay.backend.webhook.application.WebhookSecretGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookCryptographyConfigurationTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII)
    );

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WebhookCryptographyConfiguration.class);

    @Test
    void shouldCreateCryptographyPortsFromAnExplicitValidKey() {
        contextRunner
                .withPropertyValues("flowpay.webhook.secret.encryption-key=" + VALID_KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(WebhookSecretGenerator.class);
                    assertThat(context).hasSingleBean(WebhookSecretCipher.class);
                });
    }

    @Test
    void shouldFailStartupWhenTheEncryptionKeyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("webhook secret encryption key must be configured");
        });
    }

    @Test
    void shouldFailStartupWhenTheEncryptionKeyIsMalformed() {
        contextRunner
                .withPropertyValues("flowpay.webhook.secret.encryption-key=not-base64!")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "webhook secret encryption key must be valid Base64"
                            );
                });
    }

    @Test
    void shouldFailStartupWhenTheEncryptionKeyHasTheWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        contextRunner
                .withPropertyValues("flowpay.webhook.secret.encryption-key=" + shortKey)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "webhook secret encryption key must decode to exactly 32 bytes"
                            );
                });
    }

    @Test
    void shouldRedactTheEncryptionKeyFromConfigurationDiagnostics() {
        WebhookSecretProperties properties = new WebhookSecretProperties(VALID_KEY);

        assertThat(properties.toString())
                .isEqualTo("WebhookSecretProperties[encryptionKey=[REDACTED]]")
                .doesNotContain(VALID_KEY);
    }
}
