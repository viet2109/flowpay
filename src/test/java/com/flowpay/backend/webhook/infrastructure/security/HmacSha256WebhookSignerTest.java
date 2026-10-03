package com.flowpay.backend.webhook.infrastructure.security;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class HmacSha256WebhookSignerTest {
    private final HmacSha256WebhookSigner signer = new HmacSha256WebhookSigner();
    private static final byte[] BODY = "{\"id\":\"evt_known\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void matchesIndependentKnownHmacVectorAndExactHeaderFormat() {
        // Independently calculated with .NET HMACSHA256: key UTF-8 'Jefe', input 1700000000.<BODY>.
        assertThat(signer.signatureHeader(1700000000, "Jefe", BODY)).isEqualTo(
                "t=1700000000,v1=bb434a626b8d8c4b0594ac30dcb5cdb8ef0607f9554aa6a86f8626bd318a6577");
    }

    @Test
    void timestampBodyWhitespaceAndSecretMutationsChangeSignatureWithoutNormalization() {
        String original = signer.signatureHeader(1700000000, "Jefe", BODY);
        assertThat(signer.signatureHeader(1700000001, "Jefe", BODY)).isNotEqualTo(original);
        assertThat(signer.signatureHeader(1700000000, "Jefe", "{ \"id\":\"evt_known\"}\n".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(original);
        assertThat(signer.signatureHeader(1700000000, " Jefe ", BODY)).isNotEqualTo(original);
        assertThat(BODY).isEqualTo("{\"id\":\"evt_known\"}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsInvalidInputsWithoutIncludingSecrets() {
        assertThatThrownBy(() -> signer.signatureHeader(-1, "whsec_private", BODY))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid Webhook signing input");
        assertThatThrownBy(() -> signer.signatureHeader(1, " ", BODY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.signatureHeader(1, null, BODY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> signer.signatureHeader(1, "whsec_private", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void sharedSignerKeepsMacStateIsolatedAcrossConcurrentCalls() throws Exception {
        try (var executor = Executors.newFixedThreadPool(6)) {
            var tasks = java.util.stream.IntStream.range(0, 60).mapToObj(i -> executor.submit(() ->
                    signer.signatureHeader(i, "whsec_parallel_" + i, BODY))).toList();
            for (int i = 0; i < tasks.size(); i++) {
                assertThat(tasks.get(i).get(5, TimeUnit.SECONDS))
                        .isEqualTo(signer.signatureHeader(i, "whsec_parallel_" + i, BODY));
            }
        }
    }
}
