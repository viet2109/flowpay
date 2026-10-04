package com.flowpay.backend.webhook.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEndpointTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-13T08:00:00Z");
    private static final Set<WebhookEventType> INITIAL_EVENTS = Set.of(
            WebhookEventType.PAYMENT_PROCESSING,
            WebhookEventType.PAYMENT_SUCCEEDED
    );

    @Test
    void shouldCreateAnActiveEndpointWithoutRawSecretState() {
        WebhookEndpoint endpoint = createEndpoint();

        assertThat(endpoint.internalId()).isNull();
        assertThat(endpoint.publicId()).isEqualTo("wep_endpoint");
        assertThat(endpoint.merchantId()).isEqualTo(17L);
        assertThat(endpoint.url()).isEqualTo("https://merchant.example/webhooks");
        assertThat(endpoint.secretCiphertext()).isEqualTo("v1:iv:ciphertext");
        assertThat(endpoint.status()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        assertThat(endpoint.subscribedEventTypes()).containsExactlyInAnyOrderElementsOf(
                INITIAL_EVENTS
        );
        assertThat(endpoint.version()).isZero();
        assertThat(endpoint.createdAt()).isEqualTo(CREATED_AT);
        assertThat(endpoint.updatedAt()).isEqualTo(CREATED_AT);

        assertThat(WebhookEndpoint.class.getDeclaredFields())
                .extracting(field -> field.getName().toLowerCase())
                .noneMatch(name -> name.contains("rawsecret"));
        assertThat(WebhookEndpoint.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    @Test
    void shouldChangeUrlAndReplaceTheCompleteSubscriptionSetWhileActive() {
        WebhookEndpoint endpoint = createEndpoint();
        Instant changedAt = CREATED_AT.plusSeconds(10);

        endpoint.changeUrl(" https://merchant.example/new-hook ", changedAt);
        endpoint.replaceSubscriptions(
                List.of(WebhookEventType.REFUND_PROCESSING, WebhookEventType.REFUND_FAILED),
                changedAt
        );

        assertThat(endpoint.url()).isEqualTo("https://merchant.example/new-hook");
        assertThat(endpoint.subscribedEventTypes()).containsExactly(
                WebhookEventType.REFUND_PROCESSING,
                WebhookEventType.REFUND_FAILED
        );
        assertThat(endpoint.updatedAt()).isEqualTo(changedAt);
        assertThatThrownBy(() -> endpoint.subscribedEventTypes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRotateOnlyTheEncryptedSecretWhileActive() {
        WebhookEndpoint endpoint = createEndpoint();

        endpoint.rotateSecret("v1:new-iv:new-ciphertext", CREATED_AT.plusSeconds(20));

        assertThat(endpoint.secretCiphertext()).isEqualTo("v1:new-iv:new-ciphertext");
        assertThat(endpoint.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(20));
    }

    @Test
    void shouldDisableWithoutProvidingAReEnableTransition() {
        WebhookEndpoint endpoint = createEndpoint();
        Instant disabledAt = CREATED_AT.plusSeconds(30);

        endpoint.disable(disabledAt);

        assertThat(endpoint.status()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(endpoint.updatedAt()).isEqualTo(disabledAt);
        assertThat(WebhookEndpoint.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("enable"));
    }

    @Test
    void shouldRejectEveryMutationOnceDisabled() {
        WebhookEndpoint endpoint = createEndpoint();
        endpoint.disable(CREATED_AT.plusSeconds(1));

        assertThatThrownBy(() -> endpoint.changeUrl(
                "https://merchant.example/other",
                CREATED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        assertThatThrownBy(() -> endpoint.replaceSubscriptions(
                Set.of(WebhookEventType.PAYMENT_FAILED),
                CREATED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        assertThatThrownBy(() -> endpoint.rotateSecret(
                "v1:new:new",
                CREATED_AT.plusSeconds(2)
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("DISABLED");
        assertThatThrownBy(() -> endpoint.disable(CREATED_AT.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DISABLED");
    }

    @Test
    void shouldRejectEmptyDuplicateAndNullSubscriptions() {
        assertThatThrownBy(() -> WebhookEndpoint.create(
                "wep_empty",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                Set.of(),
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("empty");

        List<WebhookEventType> duplicates = List.of(
                WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookEventType.PAYMENT_SUCCEEDED
        );
        assertThatThrownBy(() -> WebhookEndpoint.create(
                "wep_duplicate",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                duplicates,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicates");

        List<WebhookEventType> containingNull = new ArrayList<>();
        containingNull.add(WebhookEventType.PAYMENT_SUCCEEDED);
        containingNull.add(null);
        assertThatThrownBy(() -> WebhookEndpoint.create(
                "wep_null_event",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                containingNull,
                CREATED_AT
        )).isInstanceOf(NullPointerException.class).hasMessageContaining("contain null");
    }

    @Test
    void shouldRehydrateValidatedPersistenceState() {
        WebhookEndpoint endpoint = WebhookEndpoint.rehydrate(
                9L,
                "wep_rehydrated",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                WebhookEndpointStatus.DISABLED,
                Set.of(WebhookEventType.REFUND_SUCCEEDED),
                4L,
                CREATED_AT,
                CREATED_AT.plusSeconds(40)
        );

        assertThat(endpoint.internalId()).isEqualTo(9L);
        assertThat(endpoint.status()).isEqualTo(WebhookEndpointStatus.DISABLED);
        assertThat(endpoint.version()).isEqualTo(4L);
        assertThat(endpoint.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(40));
    }

    @Test
    void shouldRejectInvalidIdentityStateAndMutationTime() {
        assertThatThrownBy(() -> WebhookEndpoint.create(
                "endpoint_without_prefix",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                INITIAL_EVENTS,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wep_");
        assertThatThrownBy(() -> WebhookEndpoint.create(
                "wep_invalid_merchant",
                0L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                INITIAL_EVENTS,
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");

        WebhookEndpoint endpoint = createEndpoint();
        assertThatThrownBy(() -> endpoint.changeUrl(
                "https://merchant.example/old",
                CREATED_AT.minusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("changedAt must not be before updatedAt");
    }

    @Test
    void shouldKeepMutableStatePrivateAndPreventGeneratedSetters() {
        assertThat(WebhookEndpoint.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .allMatch(field -> Modifier.isPrivate(field.getModifiers()));
        assertThat(WebhookEndpoint.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("set"));
    }

    private static WebhookEndpoint createEndpoint() {
        return WebhookEndpoint.create(
                "wep_endpoint",
                17L,
                "https://merchant.example/webhooks",
                "v1:iv:ciphertext",
                INITIAL_EVENTS,
                CREATED_AT
        );
    }
}
