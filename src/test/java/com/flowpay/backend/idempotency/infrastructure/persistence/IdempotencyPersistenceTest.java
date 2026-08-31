package com.flowpay.backend.idempotency.infrastructure.persistence;

import com.flowpay.backend.idempotency.domain.IdempotencyRecord;
import com.flowpay.backend.idempotency.domain.IdempotencyRepository;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.idempotency.domain.IdempotencyStatus;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class IdempotencyPersistenceTest extends PostgresIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T08:00:00Z");
    private static final Instant INITIAL_EXPIRY = CREATED_AT.plusSeconds(3_600);
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final String RESPONSE_PAYLOAD = """
            {
              "data": {
                "id": "pi_persistence",
                "amount": 12500,
                "status": "CREATED"
              }
            }
            """;

    @Autowired
    private IdempotencyRepository idempotencyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update(
                "TRUNCATE TABLE idempotency_records, merchants RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void shouldPersistAndReloadProcessingRecord() {
        long merchantId = insertMerchant("mrc_idempotency_processing");

        IdempotencyRecord saved = idempotencyRepository.save(newProcessingRecord(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-processing"
        ));
        IdempotencyRecord reloaded = idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("checkout-processing")
        ).orElseThrow();

        assertThat(saved.internalId()).isPositive();
        assertThat(reloaded.internalId()).isEqualTo(saved.internalId());
        assertThat(reloaded.merchantId()).isEqualTo(merchantId);
        assertThat(reloaded.operation()).isEqualTo(IdempotencyOperation.PAYMENT_INTENT_CREATE);
        assertThat(reloaded.idempotencyKey().value()).isEqualTo("checkout-processing");
        assertThat(reloaded.requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(reloaded.status()).isEqualTo(IdempotencyStatus.PROCESSING);
        assertThat(reloaded.isProcessing()).isTrue();
        assertThat(reloaded.resourceType()).isNull();
        assertThat(reloaded.responsePayload()).isNull();
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.completedAt()).isNull();
        assertThat(reloaded.expiresAt()).isEqualTo(INITIAL_EXPIRY);
    }

    @Test
    void shouldCompleteAndPersistJsonbResponseSnapshot() throws Exception {
        long merchantId = insertMerchant("mrc_idempotency_completed");
        IdempotencyRecord saved = idempotencyRepository.save(newProcessingRecord(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "checkout-completed"
        ));
        Instant completedAt = CREATED_AT.plusSeconds(12);
        Instant completedExpiry = completedAt.plusSeconds(86_400);

        saved.complete(
                "PAYMENT_INTENT",
                "pi_persistence",
                201,
                RESPONSE_PAYLOAD,
                completedAt,
                completedExpiry
        );
        IdempotencyRecord updated = idempotencyRepository.save(saved);
        IdempotencyRecord reloaded = idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("checkout-completed")
        ).orElseThrow();

        assertThat(updated.internalId()).isEqualTo(saved.internalId());
        assertThat(reloaded.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(reloaded.resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(reloaded.resourcePublicId()).isEqualTo("pi_persistence");
        assertThat(reloaded.httpStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(reloaded.responsePayload()))
                .isEqualTo(objectMapper.readTree(RESPONSE_PAYLOAD));
        assertThat(reloaded.completedAt()).isEqualTo(completedAt);
        assertThat(reloaded.expiresAt()).isEqualTo(completedExpiry);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_typeof(response_payload)::text FROM idempotency_records WHERE id = ?",
                String.class,
                saved.internalId()
        )).isEqualTo("jsonb");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT response_payload -> 'data' ->> 'id' FROM idempotency_records WHERE id = ?",
                String.class,
                saved.internalId()
        )).isEqualTo("pi_persistence");
    }

    @Test
    void shouldLookupByMerchantOperationAndCaseSensitiveKeyScope() {
        long merchantId = insertMerchant("mrc_idempotency_scope");
        long otherMerchantId = insertMerchant("mrc_idempotency_scope_other");
        IdempotencyRecord createRecord = idempotencyRepository.save(newProcessingRecord(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "Shared-Key"
        ));
        IdempotencyRecord confirmRecord = idempotencyRepository.save(newProcessingRecord(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                "Shared-Key"
        ));
        IdempotencyRecord otherMerchantRecord = idempotencyRepository.save(newProcessingRecord(
                otherMerchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                "Shared-Key"
        ));

        assertThat(idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("Shared-Key")
        )).get().extracting(IdempotencyRecord::internalId)
                .isEqualTo(createRecord.internalId());
        assertThat(idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyKey.of("Shared-Key")
        )).get().extracting(IdempotencyRecord::internalId)
                .isEqualTo(confirmRecord.internalId());
        assertThat(idempotencyRepository.findByScope(
                otherMerchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("Shared-Key")
        )).get().extracting(IdempotencyRecord::internalId)
                .isEqualTo(otherMerchantRecord.internalId());
        assertThat(idempotencyRepository.findByScope(
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CREATE,
                IdempotencyKey.of("shared-key")
                )).isEmpty();
    }

    @Test
    void shouldReleaseOnlyTheExactlyOwnedProcessingReservation() {
        long merchantId = insertMerchant("mrc_idempotency_release");
        IdempotencyRecord reservation = idempotencyRepository.tryInsert(
                IdempotencyRecord.startForResource(
                        merchantId,
                        IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                        IdempotencyKey.of("confirm-release"),
                        REQUEST_HASH,
                        "PAYMENT_INTENT",
                        "pi_release",
                        CREATED_AT,
                        INITIAL_EXPIRY
                )
        ).orElseThrow();

        boolean wrongOwnerReleased = idempotencyRepository.releaseProcessingReservation(
                reservation.internalId(),
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyKey.of("confirm-release"),
                "b".repeat(64),
                "PAYMENT_INTENT",
                "pi_release"
        );

        assertThat(wrongOwnerReleased).isFalse();
        assertThat(idempotencyRepository.findByInternalId(reservation.internalId())).isPresent();

        boolean ownerReleased = idempotencyRepository.releaseProcessingReservation(
                reservation.internalId(),
                merchantId,
                IdempotencyOperation.PAYMENT_INTENT_CONFIRM,
                IdempotencyKey.of("confirm-release"),
                REQUEST_HASH,
                "PAYMENT_INTENT",
                "pi_release"
        );

        assertThat(ownerReleased).isTrue();
        assertThat(idempotencyRepository.findByInternalId(reservation.internalId())).isEmpty();
    }

    @Test
    void repositoryPortShouldNotLeakPersistenceTypes() {
        assertThat(Arrays.stream(IdempotencyRepository.class.getDeclaredMethods())
                .map(Method::toGenericString))
                .noneMatch(signature -> signature.contains(".infrastructure.persistence."))
                .noneMatch(signature -> signature.contains("org.springframework.data."))
                .noneMatch(signature -> signature.contains("jakarta.persistence."));
        assertThat(Modifier.isPublic(IdempotencyRecordEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(IdempotencyJpaRepository.class.getModifiers())).isFalse();
    }

    private IdempotencyRecord newProcessingRecord(
            long merchantId,
            IdempotencyOperation operation,
            String idempotencyKey
    ) {
        return IdempotencyRecord.start(
                merchantId,
                operation,
                IdempotencyKey.of(idempotencyKey),
                REQUEST_HASH,
                CREATED_AT,
                INITIAL_EXPIRY
        );
    }

    private long insertMerchant(String publicId) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO merchants (public_id, name, status, created_at, updated_at, version)
                VALUES (?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                "Idempotency Store",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }
}
