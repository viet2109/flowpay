package com.flowpay.backend.webhook.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class WebhookEventTest {
    private static final Instant OCCURRED = Instant.parse("2026-10-03T10:00:00.123456Z");
    private static final String PAYLOAD = " {\"id\":\"evt_one\",\"data\":{\"payment\":{\"id\":\"pi_one\"}}} ";

    @Test
    void createsAnImmutableSnapshotAndRehydratesWithoutReconstructingPayload() {
        WebhookEvent event = create(WebhookEventType.PAYMENT_SUCCEEDED);
        assertThat(event.internalId()).isNull();
        assertThat(event.publicId()).isEqualTo("evt_one");
        assertThat(event.sourceEventId()).isEqualTo("source_one");
        assertThat(event.merchantId()).isEqualTo(1);
        assertThat(event.payload()).isEqualTo(PAYLOAD);
        assertThat(event.occurredAt()).isEqualTo(OCCURRED);
        assertThat(event.createdAt()).isEqualTo(OCCURRED.plusSeconds(5));
        WebhookEvent read = WebhookEvent.rehydrate(10, event.publicId(), event.sourceEventId(), event.merchantId(),
                event.eventType(), event.resourceType(), event.resourceId(), event.payload(), event.occurredAt(), event.createdAt());
        assertThat(read).usingRecursiveComparison().ignoringFields("internalId").isEqualTo(event);
        assertThat(read.internalId()).isEqualTo(10);
        assertThat(Arrays.stream(WebhookEvent.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers())))
                .allMatch(field -> Modifier.isFinal(field.getModifiers()));
        assertThat(Arrays.stream(WebhookEvent.class.getMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.startsWith("set"));
    }

    @ParameterizedTest
    @EnumSource(WebhookEventType.class)
    void acceptsExactlyTheMatchingResourceTypeAndPublicIdPrefix(WebhookEventType type) {
        WebhookEvent event = create(type);
        WebhookResourceType resource = WebhookResourceType.forEventType(type);
        assertThat(event.resourceType()).isEqualTo(resource);
        WebhookResourceType other = resource == WebhookResourceType.REFUND
                ? WebhookResourceType.PAYMENT_INTENT : WebhookResourceType.REFUND;
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", "source_one", 1, type, other,
                other.publicIdPrefix() + "one", PAYLOAD, OCCURRED, OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", "source_one", 1, type, resource,
                other.publicIdPrefix() + "one", PAYLOAD, OCCURRED, OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidIdentityOwnershipPayloadAndTime() {
        for (String id : new String[]{"evt_", "wdl_one", "evt_" + "x".repeat(61), " "}) {
            assertThatThrownBy(() -> WebhookEvent.create(id, "source_one", 1, WebhookEventType.PAYMENT_SUCCEEDED,
                    WebhookResourceType.PAYMENT_INTENT, "pi_one", PAYLOAD, OCCURRED, OCCURRED))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", " ", 1, WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookResourceType.PAYMENT_INTENT, "pi_one", PAYLOAD, OCCURRED, OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", "source_one", 0, WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookResourceType.PAYMENT_INTENT, "pi_one", PAYLOAD, OCCURRED, OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", "source_one", 1, WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookResourceType.PAYMENT_INTENT, "pi_one", " ", OCCURRED, OCCURRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookEvent.create("evt_one", "source_one", 1, WebhookEventType.PAYMENT_SUCCEEDED,
                WebhookResourceType.PAYMENT_INTENT, "pi_one", PAYLOAD, null, OCCURRED))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WebhookEvent.rehydrate(0, "evt_one", "source_one", 1,
                WebhookEventType.PAYMENT_SUCCEEDED, WebhookResourceType.PAYMENT_INTENT,
                "pi_one", PAYLOAD, OCCURRED, OCCURRED)).isInstanceOf(IllegalArgumentException.class);
    }

    private static WebhookEvent create(WebhookEventType type) {
        WebhookResourceType resource = WebhookResourceType.forEventType(type);
        return WebhookEvent.create(" evt_one ", " source_one ", 1, type, resource,
                resource.publicIdPrefix() + "one", PAYLOAD, OCCURRED, OCCURRED.plusSeconds(5));
    }
}
