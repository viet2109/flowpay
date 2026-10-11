package com.flowpay.backend.webhook.infrastructure.url;

import com.flowpay.backend.webhook.application.WebhookHttpDeliveryRequest;
import com.flowpay.backend.webhook.application.WebhookHttpDeliveryResult;
import com.flowpay.backend.webhook.application.WebhookSigner;
import com.flowpay.backend.webhook.infrastructure.http.ApacheWebhookHttpClientAdapter;
import com.flowpay.backend.webhook.infrastructure.http.WebhookHttpProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DefaultWebhookUrlPolicyTest {

    private final DefaultWebhookUrlPolicy productionPolicy =
            new DefaultWebhookUrlPolicy(false);

    @Test
    void outboundAdapterRechecksProductionPolicyBeforeSigningOrSendingAPreviouslyStoredUrl() {
        var client = mock(CloseableHttpAsyncClient.class);
        var signer = mock(WebhookSigner.class);
        var timeout = Duration.ofSeconds(2);
        var adapter = new ApacheWebhookHttpClientAdapter(client, signer, productionPolicy,
                new WebhookHttpProperties(timeout, Duration.ofSeconds(5)),
                new com.flowpay.backend.common.observability.ExternalCallTracing(io.micrometer.tracing.Tracer.NOOP));
        clearInvocations(client);
        for (String target : new String[]{"http://127.0.0.1/webhooks", "https://localhost/webhooks",
                "https://127.0.0.1/webhooks", "https://[::1]/webhooks", "https://10.0.0.1/webhooks",
                "https://192.168.1.1/webhooks", "https://169.254.169.254/latest/meta-data"}) {
            var result = adapter.send(new WebhookHttpDeliveryRequest(target, "evt_fixture", new byte[]{1},
                    "whsec_fixture", 1700000000));
            assertThat(result.failure()).isEqualTo(WebhookHttpDeliveryResult.Failure.INVALID_URL);
            assertThat(result.httpStatus()).isNull();
            assertThat(result.toString()).doesNotContain(target, "whsec_fixture");
        }
        verifyNoInteractions(client, signer);
    }

    @Test
    void shouldAcceptAValidHttpsEndpointWithoutResolvingItsHost() {
        assertThat(productionPolicy.validate(
                " https://hooks.merchant.example:8443/flowpay?source=payment "
        )).isEqualTo("https://hooks.merchant.example:8443/flowpay?source=payment");
        assertThat(productionPolicy.validate("https://8.8.8.8/flowpay"))
                .isEqualTo("https://8.8.8.8/flowpay");
        assertThat(productionPolicy.validate("https://[2001:4860:4860::8888]/flowpay"))
                .isEqualTo("https://[2001:4860:4860::8888]/flowpay");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://merchant.example/webhooks",
            "ftp://merchant.example/webhooks",
            "http://8.8.8.8/webhooks"
    })
    void shouldRequireHttpsInProduction(String url) {
        assertThatThrownBy(() -> productionPolicy.validate(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/webhooks",
            "https://service.localhost/webhooks",
            "https://127.0.0.1/webhooks",
            "https://[::1]/webhooks",
            "https://10.0.0.1/webhooks",
            "https://172.16.0.1/webhooks",
            "https://192.168.1.1/webhooks",
            "https://169.254.169.254/latest/meta-data",
            "https://100.100.100.200/latest/meta-data",
            "https://0.0.0.0/webhooks",
            "https://224.0.0.1/webhooks",
            "https://[fe80::1]/webhooks",
            "https://[fd00::1]/webhooks",
            "https://[::]/webhooks",
            "https://100.64.0.1/hooks", "https://192.0.0.1/hooks", "https://192.0.2.1/hooks",
            "https://192.88.99.1/hooks", "https://198.18.0.1/hooks", "https://198.51.100.1/hooks",
            "https://203.0.113.1/hooks", "https://240.0.0.1/hooks", "https://255.255.255.255/hooks",
            "https://[::ffff:127.0.0.1]/hooks", "https://[64:ff9b::a00:1]/hooks",
            "https://[100::1]/hooks", "https://[2001::1]/hooks", "https://[2001:db8::1]/hooks",
            "https://[2002::1]/hooks", "https://[3fff::1]/hooks", "https://[4000::1]/hooks"
    })
    void shouldRejectLocalPrivateMetadataAndNonRoutableLiteralTargets(String url) {
        assertThatThrownBy(() -> productionPolicy.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://user:password@merchant.example/webhooks",
            "https://merchant.example/webhooks#fragment",
            "https:///missing-host",
            "merchant.example/webhooks",
            "not a URI"
    })
    void shouldRejectAmbiguousOrUnsafeUriForms(String url) {
        assertThatThrownBy(() -> productionPolicy.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:8080/webhooks",
            "http://service.localhost:8080/webhooks",
            "http://127.0.0.1:8080/webhooks",
            "http://[::1]:8080/webhooks"
    })
    void shouldAllowExplicitInsecureLocalhostForControlledTestServers(String url) {
        DefaultWebhookUrlPolicy localTestPolicy = new DefaultWebhookUrlPolicy(true);

        assertThat(localTestPolicy.validate(url)).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://merchant.example/webhooks",
            "http://10.0.0.1/webhooks",
            "https://192.168.1.1/webhooks"
    })
    void localhostSwitchShouldNotPermitGeneralInsecureOrPrivateTargets(String url) {
        DefaultWebhookUrlPolicy localTestPolicy = new DefaultWebhookUrlPolicy(true);

        assertThatThrownBy(() -> localTestPolicy.validate(url))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
