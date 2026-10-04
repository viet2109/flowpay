package com.flowpay.backend.webhook.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public final class WebhookEndpoint {

    private static final String PUBLIC_ID_PREFIX = "wep_";
    private static final int PUBLIC_ID_MAX_LENGTH = 64;
    private static final int URL_MAX_LENGTH = 2048;

    private final Long internalId;
    private final String publicId;
    private final long merchantId;
    private String url;
    private String secretCiphertext;
    private WebhookEndpointStatus status;
    private Set<WebhookEventType> subscribedEventTypes;
    private final long version;
    private final Instant createdAt;
    private Instant updatedAt;

    private WebhookEndpoint(
            Long internalId,
            String publicId,
            long merchantId,
            String url,
            String secretCiphertext,
            WebhookEndpointStatus status,
            Collection<WebhookEventType> subscribedEventTypes,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.internalId = validateInternalId(internalId);
        this.publicId = validatePublicId(publicId);
        this.merchantId = validateMerchantId(merchantId);
        this.url = validateUrl(url);
        this.secretCiphertext = requireText(secretCiphertext, "secretCiphertext");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.subscribedEventTypes = copySubscriptions(subscribedEventTypes);
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    public static WebhookEndpoint create(
            String publicId,
            long merchantId,
            String url,
            String secretCiphertext,
            Collection<WebhookEventType> subscribedEventTypes,
            Instant now
    ) {
        Instant createdAt = Objects.requireNonNull(now, "now must not be null");
        return new WebhookEndpoint(
                null,
                publicId,
                merchantId,
                url,
                secretCiphertext,
                WebhookEndpointStatus.ACTIVE,
                subscribedEventTypes,
                0L,
                createdAt,
                createdAt
        );
    }

    public static WebhookEndpoint rehydrate(
            long internalId,
            String publicId,
            long merchantId,
            String url,
            String secretCiphertext,
            WebhookEndpointStatus status,
            Collection<WebhookEventType> subscribedEventTypes,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        return new WebhookEndpoint(
                internalId,
                publicId,
                merchantId,
                url,
                secretCiphertext,
                status,
                subscribedEventTypes,
                version,
                createdAt,
                updatedAt
        );
    }

    public void changeUrl(String newUrl, Instant changedAt) {
        requireActive();
        String validatedUrl = validateUrl(newUrl);
        Instant changeTime = validateChangeTime(changedAt);
        url = validatedUrl;
        updatedAt = changeTime;
    }

    public void replaceSubscriptions(
            Collection<WebhookEventType> newSubscriptions,
            Instant changedAt
    ) {
        requireActive();
        Set<WebhookEventType> validatedSubscriptions = copySubscriptions(newSubscriptions);
        Instant changeTime = validateChangeTime(changedAt);
        subscribedEventTypes = validatedSubscriptions;
        updatedAt = changeTime;
    }

    public void rotateSecret(String newSecretCiphertext, Instant changedAt) {
        requireActive();
        String validatedCiphertext = requireText(newSecretCiphertext, "secretCiphertext");
        Instant changeTime = validateChangeTime(changedAt);
        secretCiphertext = validatedCiphertext;
        updatedAt = changeTime;
    }

    public void disable(Instant changedAt) {
        requireActive();
        Instant changeTime = validateChangeTime(changedAt);
        status = WebhookEndpointStatus.DISABLED;
        updatedAt = changeTime;
    }

    private void requireActive() {
        if (status != WebhookEndpointStatus.ACTIVE) {
            throw new IllegalStateException("WebhookEndpoint cannot be mutated from " + status);
        }
    }

    private Instant validateChangeTime(Instant changedAt) {
        Instant changeTime = Objects.requireNonNull(changedAt, "changedAt must not be null");
        if (changeTime.isBefore(updatedAt)) {
            throw new IllegalArgumentException("changedAt must not be before updatedAt");
        }
        return changeTime;
    }

    private static Long validateInternalId(Long internalId) {
        if (internalId != null && internalId <= 0) {
            throw new IllegalArgumentException("internalId must be positive");
        }
        return internalId;
    }

    private static String validatePublicId(String publicId) {
        String value = requireText(publicId, "publicId");
        if (!value.startsWith(PUBLIC_ID_PREFIX) || value.length() == PUBLIC_ID_PREFIX.length()) {
            throw new IllegalArgumentException(
                    "publicId must start with wep_ and contain an identifier"
            );
        }
        if (value.length() > PUBLIC_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "publicId must not exceed " + PUBLIC_ID_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static long validateMerchantId(long merchantId) {
        if (merchantId <= 0) {
            throw new IllegalArgumentException("merchantId must be positive");
        }
        return merchantId;
    }

    private static String validateUrl(String url) {
        String value = requireText(url, "url");
        if (value.length() > URL_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "url must not exceed " + URL_MAX_LENGTH + " characters"
            );
        }
        return value;
    }

    private static Set<WebhookEventType> copySubscriptions(
            Collection<WebhookEventType> subscriptions
    ) {
        Objects.requireNonNull(subscriptions, "subscribedEventTypes must not be null");
        if (subscriptions.isEmpty()) {
            throw new IllegalArgumentException("subscribedEventTypes must not be empty");
        }
        EnumSet<WebhookEventType> copied = EnumSet.noneOf(WebhookEventType.class);
        for (WebhookEventType eventType : subscriptions) {
            WebhookEventType value = Objects.requireNonNull(
                    eventType,
                    "subscribedEventTypes must not contain null"
            );
            if (!copied.add(value)) {
                throw new IllegalArgumentException(
                        "subscribedEventTypes must not contain duplicates"
                );
            }
        }
        return Collections.unmodifiableSet(copied);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    public Long internalId() {
        return internalId;
    }

    public String publicId() {
        return publicId;
    }

    public long merchantId() {
        return merchantId;
    }

    public String url() {
        return url;
    }

    public String secretCiphertext() {
        return secretCiphertext;
    }

    public WebhookEndpointStatus status() {
        return status;
    }

    public Set<WebhookEventType> subscribedEventTypes() {
        return subscribedEventTypes;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
