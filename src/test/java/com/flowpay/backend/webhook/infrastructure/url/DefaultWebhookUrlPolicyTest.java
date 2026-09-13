package com.flowpay.backend.webhook.infrastructure.url;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultWebhookUrlPolicyTest {

    private final DefaultWebhookUrlPolicy productionPolicy =
            new DefaultWebhookUrlPolicy(false);

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
            "https://[::]/webhooks"
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
