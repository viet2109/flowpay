package com.flowpay.backend.idempotency.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyRecordTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");
    private static final Instant INITIAL_EXPIRY = CREATED_AT.plusSeconds(3_600);
    private static final String REQUEST_HASH = "a".repeat(64);

    @Test
    void shouldStartInProcessingState() {
        IdempotencyRecord record = newProcessingRecord();

        assertThat(record.internalId()).isNull();
        assertThat(record.merchantId()).isEqualTo(41L);
        assertThat(record.operation()).isEqualTo("PAYMENT_INTENT_CREATE");
        assertThat(record.idempotencyKey()).isEqualTo("checkout-1001");
        assertThat(record.requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(record.status()).isEqualTo(IdempotencyStatus.PROCESSING);
        assertThat(record.isProcessing()).isTrue();
        assertThat(record.isCompleted()).isFalse();
        assertThat(record.resourceType()).isNull();
        assertThat(record.resourcePublicId()).isNull();
        assertThat(record.httpStatus()).isNull();
        assertThat(record.responsePayload()).isNull();
        assertThat(record.createdAt()).isEqualTo(CREATED_AT);
        assertThat(record.completedAt()).isNull();
        assertThat(record.expiresAt()).isEqualTo(INITIAL_EXPIRY);
    }

    @Test
    void shouldCompleteProcessingRecordWithStableResponseSnapshot() {
        IdempotencyRecord record = newProcessingRecord();
        Instant completedAt = CREATED_AT.plusSeconds(10);
        Instant completedExpiry = completedAt.plusSeconds(86_400);
        String responsePayload = """
                {"data":{"id":"pi_1001","status":"CREATED"}}
                """;

        record.complete(
                "PAYMENT_INTENT",
                "pi_1001",
                201,
                responsePayload,
                completedAt,
                completedExpiry
        );

        assertThat(record.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.isCompleted()).isTrue();
        assertThat(record.isProcessing()).isFalse();
        assertThat(record.resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(record.resourcePublicId()).isEqualTo("pi_1001");
        assertThat(record.httpStatus()).isEqualTo(201);
        assertThat(record.responsePayload()).isEqualTo(responsePayload);
        assertThat(record.completedAt()).isEqualTo(completedAt);
        assertThat(record.expiresAt()).isEqualTo(completedExpiry);
    }

    @Test
    void shouldRejectCompletingAnAlreadyCompletedRecord() {
        IdempotencyRecord record = newProcessingRecord();
        Instant completedAt = CREATED_AT.plusSeconds(10);
        record.complete(
                "PAYMENT_INTENT",
                "pi_1001",
                201,
                "{\"data\":{}}",
                completedAt,
                completedAt.plusSeconds(86_400)
        );

        assertThatThrownBy(() -> record.complete(
                "PAYMENT_INTENT",
                "pi_1002",
                200,
                "{\"data\":{}}",
                completedAt.plusSeconds(1),
                completedAt.plusSeconds(86_401)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Only a PROCESSING idempotency record can be completed");

        assertThat(record.resourcePublicId()).isEqualTo("pi_1001");
        assertThat(record.httpStatus()).isEqualTo(201);
    }

    @Test
    void shouldCalculateExpirationUsingStrictBeforeSemantics() {
        IdempotencyRecord record = newProcessingRecord();

        assertThat(record.isExpired(INITIAL_EXPIRY.minusNanos(1))).isFalse();
        assertThat(record.isExpired(INITIAL_EXPIRY)).isFalse();
        assertThat(record.isExpired(INITIAL_EXPIRY.plusNanos(1))).isTrue();
    }

    @Test
    void shouldRejectInconsistentRehydratedLifecycleState() {
        assertThatThrownBy(() -> IdempotencyRecord.rehydrate(
                7L,
                41L,
                "PAYMENT_INTENT_CREATE",
                "checkout-1001",
                REQUEST_HASH,
                IdempotencyStatus.COMPLETED,
                "PAYMENT_INTENT",
                "pi_1001",
                null,
                null,
                CREATED_AT,
                CREATED_AT.plusSeconds(10),
                INITIAL_EXPIRY
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a COMPLETED idempotency record must have response and completion data");

        assertThatThrownBy(() -> IdempotencyRecord.rehydrate(
                7L,
                41L,
                "PAYMENT_INTENT_CREATE",
                "checkout-1001",
                REQUEST_HASH,
                IdempotencyStatus.PROCESSING,
                null,
                null,
                202,
                "{\"data\":{}}",
                CREATED_AT,
                null,
                INITIAL_EXPIRY
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a PROCESSING idempotency record must not have completion data");
    }

    @Test
    void shouldRejectInvalidHashAndTimestampOrder() {
        assertThatThrownBy(() -> IdempotencyRecord.start(
                41L,
                "PAYMENT_INTENT_CREATE",
                "checkout-1001",
                "ABC",
                CREATED_AT,
                INITIAL_EXPIRY
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestHash must contain exactly 64 lowercase hexadecimal characters");

        assertThatThrownBy(() -> IdempotencyRecord.start(
                41L,
                "PAYMENT_INTENT_CREATE",
                "checkout-1001",
                REQUEST_HASH,
                CREATED_AT,
                CREATED_AT.minusNanos(1)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expiresAt must not be before createdAt");
    }

    private static IdempotencyRecord newProcessingRecord() {
        return IdempotencyRecord.start(
                41L,
                "PAYMENT_INTENT_CREATE",
                "checkout-1001",
                REQUEST_HASH,
                CREATED_AT,
                INITIAL_EXPIRY
        );
    }
}
