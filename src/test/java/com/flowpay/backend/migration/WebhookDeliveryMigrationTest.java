package com.flowpay.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WebhookDeliveryMigrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-03T10:00:00.123456Z");
    private static final List<String> EVENT_TYPES = List.of(
            "payment.processing", "payment.succeeded", "payment.failed",
            "refund.processing", "refund.succeeded", "refund.failed"
    );

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    private static JdbcTemplate jdbc;
    private static List<String> previousHistory;
    private static List<String> upgradedHistory;
    private static List<String> upgradedConfiguration;

    @BeforeAll
    static void applyMigrations() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        ));
        migrationsThrough("10").migrate();
        previousHistory = migrationHistory();
        long merchantId = insertMerchant("mrc_upgrade");
        long endpointId = insertEndpoint("wep_upgrade", merchantId);
        jdbc.update("INSERT INTO webhook_endpoint_events (endpoint_id, event_type) VALUES (?, ?)",
                endpointId, "payment.succeeded");

        Flyway current = migrationsThrough("11");
        current.migrate();
        current.validate();
        upgradedHistory = migrationHistory();
        upgradedConfiguration = jdbc.queryForList("""
                SELECT m.public_id || ':' || e.public_id || ':' || e.status || ':' || s.event_type
                FROM merchants m
                JOIN webhook_endpoints e ON e.merchant_id = m.id
                JOIN webhook_endpoint_events s ON s.endpoint_id = e.id
                """, String.class);
    }

    @BeforeEach
    void clearWebhookData() {
        jdbc.update("""
                TRUNCATE TABLE webhook_delivery_attempts, webhook_deliveries, webhook_events,
                    webhook_endpoint_events, webhook_endpoints, merchants
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldApplyCleanV001ThroughV011AndPreservePriorChecksumsAndConfiguration() {
        assertThat(jdbc.queryForList("""
                SELECT version::integer FROM flyway_schema_history
                WHERE success = true ORDER BY installed_rank
                """, Integer.class)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        assertThat(upgradedHistory.subList(0, 10)).containsExactlyElementsOf(previousHistory);
        assertThat(upgradedConfiguration).containsExactly("mrc_upgrade:wep_upgrade:ACTIVE:payment.succeeded");

        assertThat(columns("webhook_events")).containsExactly(
                "id:bigint:NO", "public_id:character varying:NO", "source_event_id:character varying:NO",
                "merchant_id:bigint:NO", "event_type:character varying:NO", "resource_type:character varying:NO",
                "resource_id:character varying:NO", "payload:jsonb:NO",
                "occurred_at:timestamp with time zone:NO", "created_at:timestamp with time zone:NO"
        );
        assertThat(columns("webhook_deliveries")).containsExactly(
                "id:bigint:NO", "public_id:character varying:NO", "webhook_event_id:bigint:NO",
                "webhook_endpoint_id:bigint:NO", "status:character varying:NO", "attempt_count:integer:NO",
                "next_attempt_at:timestamp with time zone:YES", "lease_expires_at:timestamp with time zone:YES",
                "delivered_at:timestamp with time zone:YES", "last_http_status:integer:YES", "last_error:text:YES",
                "created_at:timestamp with time zone:NO", "updated_at:timestamp with time zone:NO", "version:bigint:NO"
        );
        assertThat(columns("webhook_delivery_attempts")).containsExactly(
                "id:bigint:NO", "delivery_id:bigint:NO", "attempt_no:integer:NO",
                "started_at:timestamp with time zone:NO", "finished_at:timestamp with time zone:YES",
                "http_status:integer:YES", "duration_ms:integer:YES", "error_message:text:YES",
                "created_at:timestamp with time zone:NO"
        );
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'id' AND is_identity = 'YES'
                    AND table_name IN ('webhook_events', 'webhook_deliveries', 'webhook_delivery_attempts')
                """, String.class)).containsExactlyInAnyOrder(
                "webhook_events", "webhook_deliveries", "webhook_delivery_attempts"
        );
    }

    @Test
    void shouldCreateDedupeWorkerLeaseAndHistoryIndexes() {
        assertThat(jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                    AND tablename IN ('webhook_events', 'webhook_deliveries', 'webhook_delivery_attempts')
                """, String.class)).containsExactlyInAnyOrder(
                "pk_webhook_events", "uq_webhook_events_public_id", "uq_webhook_events_source_event_id",
                "pk_webhook_deliveries", "uq_webhook_deliveries_public_id", "uq_webhook_deliveries_event_endpoint",
                "ix_webhook_deliveries_status_next_attempt_at", "ix_webhook_deliveries_status_lease_expires_at",
                "ix_webhook_deliveries_endpoint_created_at", "pk_webhook_delivery_attempts",
                "uq_webhook_delivery_attempts_delivery_attempt_no"
        );
        assertIndex("ix_webhook_deliveries_status_next_attempt_at", "(status, next_attempt_at)");
        assertIndex("ix_webhook_deliveries_status_lease_expires_at", "(status, lease_expires_at)");
        assertIndex("ix_webhook_deliveries_endpoint_created_at", "(webhook_endpoint_id, created_at DESC)");
        assertIndex("uq_webhook_deliveries_event_endpoint", "(webhook_event_id, webhook_endpoint_id)");
        assertIndex("uq_webhook_delivery_attempts_delivery_attempt_no", "(delivery_id, attempt_no)");
    }

    @Test
    void shouldEnforceGlobalEventPublicAndSourceIdentityAndMerchantOwnership() {
        long merchantId = insertMerchant("mrc_events");
        long anotherMerchant = insertMerchant("mrc_events_other");
        long eventId = insertEvent("evt_original", "evt_source", merchantId, "payment.succeeded", "PAYMENT_INTENT");
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertEvent("evt_original", "evt_other_source", anotherMerchant, "refund.succeeded", "REFUND"));
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertEvent("evt_another", "evt_source", anotherMerchant, "payment.failed", "PAYMENT_INTENT"));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertEvent("evt_unknown_owner", "evt_unknown_source", 9_999_999L, "refund.failed", "REFUND"));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> jdbc.update("DELETE FROM merchants WHERE id = ?", merchantId));
        assertThat(jdbc.queryForObject("SELECT payload ->> 'id' FROM webhook_events WHERE id = ?",
                String.class, eventId)).isEqualTo("evt_original");
    }

    @ParameterizedTest
    @MethodSource("eventTypes")
    void shouldAcceptAllSixPublicEventTypesWithoutSourceModuleForeignKeys(String eventType) {
        long merchantId = insertMerchant("mrc_type");
        String resourceType = eventType.startsWith("payment.") ? "PAYMENT_INTENT" : "REFUND";
        long id = insertEvent("evt_type", "evt_source_type", merchantId, eventType, resourceType);
        assertThat(jdbc.queryForObject("SELECT event_type FROM webhook_events WHERE id = ?", String.class, id))
                .isEqualTo(eventType);
        // Resource public IDs need not exist in Payment/Refund persistence.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_intents", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Long.class)).isZero();
    }

    @Test
    void shouldRejectUnsupportedEventAndResourceTypes() {
        long merchantId = insertMerchant("mrc_invalid_types");
        for (String type : List.of("payment.cancelled", "payment.succeeded.v1", "refund.unknown")) {
            assertSqlState(CHECK_VIOLATION,
                    () -> insertEvent("evt_bad", "evt_bad_source", merchantId, type, "PAYMENT_INTENT"));
        }
        assertSqlState(CHECK_VIOLATION,
                () -> insertEvent("evt_bad", "evt_bad_source", merchantId, "payment.succeeded", "MERCHANT"));
    }

    @Test
    void shouldEnforceDeliveryPublicIdentityAndEventEndpointPair() {
        Fixture fixture = fixture();
        long deliveryId = insertDelivery("wdl_original", fixture, "PENDING", NOW, null, null);
        long secondEvent = insertEvent("evt_second", "evt_source_second", fixture.merchantId(),
                "refund.succeeded", "REFUND");
        long secondEndpoint = insertEndpoint("wep_second", fixture.merchantId());
        Fixture anotherPair = new Fixture(fixture.merchantId(), secondEvent, secondEndpoint);
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertDelivery("wdl_original", anotherPair, "PENDING", NOW, null, null));
        assertSqlState(UNIQUE_VIOLATION,
                () -> insertDelivery("wdl_duplicate_pair", fixture, "PENDING", NOW, null, null));
        insertDelivery("wdl_second_endpoint", new Fixture(fixture.merchantId(), fixture.eventId(), secondEndpoint),
                "PENDING", NOW, null, null);
        insertDelivery("wdl_second_event", new Fixture(fixture.merchantId(), secondEvent, fixture.endpointId()),
                "PENDING", NOW, null, null);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM webhook_deliveries WHERE id = ?",
                Integer.class, deliveryId)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_deliveries WHERE id = ?",
                Long.class, deliveryId)).isZero();
    }

    @Test
    void shouldRetainDeliveryAndAttemptHistoryThroughNonCascadingForeignKeys() {
        Fixture fixture = fixture();
        long deliveryId = insertDelivery("wdl_retained", fixture, "PENDING", NOW, null, null);
        insertAttempt(deliveryId, 1, null, null, null);
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertDelivery("wdl_no_event", new Fixture(fixture.merchantId(), 9_999_999L, fixture.endpointId()),
                        "PENDING", NOW, null, null));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> insertDelivery("wdl_no_endpoint", new Fixture(fixture.merchantId(), fixture.eventId(), 9_999_999L),
                        "PENDING", NOW, null, null));
        assertSqlState(FOREIGN_KEY_VIOLATION, () -> insertAttempt(9_999_999L, 1, null, null, null));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> jdbc.update("DELETE FROM webhook_events WHERE id = ?", fixture.eventId()));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> jdbc.update("DELETE FROM webhook_endpoints WHERE id = ?", fixture.endpointId()));
        assertSqlState(FOREIGN_KEY_VIOLATION,
                () -> jdbc.update("DELETE FROM webhook_deliveries WHERE id = ?", deliveryId));
        assertThat(jdbc.queryForList("""
                SELECT c.conname || ':' || target.relname || ':' || c.confdeltype::text
                FROM pg_constraint c
                JOIN pg_class source ON source.oid = c.conrelid
                JOIN pg_class target ON target.oid = c.confrelid
                JOIN pg_namespace n ON n.oid = source.relnamespace
                WHERE n.nspname = 'public' AND c.contype = 'f'
                    AND source.relname IN ('webhook_events', 'webhook_deliveries', 'webhook_delivery_attempts')
                """, String.class)).containsExactlyInAnyOrder(
                "fk_webhook_events_merchant:merchants:a",
                "fk_webhook_deliveries_event:webhook_events:a",
                "fk_webhook_deliveries_endpoint:webhook_endpoints:a",
                "fk_webhook_delivery_attempts_delivery:webhook_deliveries:a"
        );
    }

    @ParameterizedTest(name = "{0} scheduling timestamp mask={1}")
    @MethodSource("deliveryTimestampCombinations")
    void shouldEnforceEveryStatusAndTimestampCombination(String status, int timestampMask) {
        Fixture fixture = fixture();
        OffsetDateTime nextAttemptAt = (timestampMask & 1) != 0 ? NOW : null;
        OffsetDateTime leaseExpiresAt = (timestampMask & 2) != 0 ? NOW.plusSeconds(30) : null;
        OffsetDateTime deliveredAt = (timestampMask & 4) != 0 ? NOW : null;
        int validMask = switch (status) {
            case "PENDING", "RETRYING" -> 1;
            case "DELIVERING" -> 2;
            case "DELIVERED" -> 4;
            case "DEAD" -> 0;
            default -> throw new IllegalArgumentException("Unexpected test status");
        };
        if (timestampMask == validMask) {
            long id = insertDelivery("wdl_timestamps", fixture, status, nextAttemptAt, leaseExpiresAt, deliveredAt);
            assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, id))
                    .isEqualTo(status);
        } else {
            assertSqlState(CHECK_VIOLATION,
                    () -> insertDelivery("wdl_timestamps", fixture, status, nextAttemptAt, leaseExpiresAt, deliveredAt));
        }
    }

    @Test
    void shouldRejectUnsupportedStatusAndNegativeAttemptCount() {
        Fixture fixture = fixture();
        assertSqlState(CHECK_VIOLATION,
                () -> insertDelivery("wdl_invalid_status", fixture, "FAILED", NOW, null, null));
        long id = insertDelivery("wdl_count", fixture, "PENDING", NOW, null, null);
        assertSqlState(CHECK_VIOLATION,
                () -> jdbc.update("UPDATE webhook_deliveries SET attempt_count = -1 WHERE id = ?", id));
        jdbc.update("UPDATE webhook_deliveries SET attempt_count = 1 WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM webhook_deliveries WHERE id = ?", Integer.class, id))
                .isOne();
    }

    @Test
    void shouldEnforceHttpStatusBoundsForDeliveriesAndAttempts() {
        long id = insertDelivery("wdl_http", fixture(), "PENDING", NOW, null, null);
        for (Integer status : new Integer[]{null, 100, 200, 599}) {
            jdbc.update("UPDATE webhook_deliveries SET last_http_status = ? WHERE id = ?", status, id);
            insertAttempt(id, status == null ? 1 : status, NOW, status, 0);
        }
        for (int status : new int[]{-1, 0, 99, 600}) {
            assertSqlState(CHECK_VIOLATION,
                    () -> jdbc.update("UPDATE webhook_deliveries SET last_http_status = ? WHERE id = ?", status, id));
            assertSqlState(CHECK_VIOLATION, () -> insertAttempt(id, 2, NOW, status, 0));
        }
    }

    @Test
    void shouldEnforcePositiveUniqueAttemptNumbersAndNonNegativeOptionalDuration() {
        long deliveryId = insertDelivery("wdl_attempts", fixture(), "DELIVERING", null, NOW.plusSeconds(30), null);
        insertAttempt(deliveryId, 1, null, null, null);
        insertAttempt(deliveryId, 2, NOW.plusSeconds(1), 200, 0);
        insertAttempt(deliveryId, 3, NOW.plusSeconds(1), null, 1000);
        assertSqlState(UNIQUE_VIOLATION, () -> insertAttempt(deliveryId, 1, NOW, 200, 1));
        assertSqlState(CHECK_VIOLATION, () -> insertAttempt(deliveryId, 0, null, null, null));
        assertSqlState(CHECK_VIOLATION, () -> insertAttempt(deliveryId, -1, null, null, null));
        assertSqlState(CHECK_VIOLATION, () -> insertAttempt(deliveryId, 4, NOW, null, -1));

        long anotherDelivery = insertDelivery("wdl_other_attempts", fixture(), "PENDING", NOW, null, null);
        insertAttempt(anotherDelivery, 1, null, null, null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_delivery_attempts", Long.class)).isEqualTo(4L);
    }

    private static Stream<String> eventTypes() {
        return EVENT_TYPES.stream();
    }

    private static Stream<Arguments> deliveryTimestampCombinations() {
        return Stream.of("PENDING", "RETRYING", "DELIVERING", "DELIVERED", "DEAD")
                .flatMap(status -> IntStream.range(0, 8).mapToObj(mask -> Arguments.of(status, mask)));
    }

    private static Flyway migrationsThrough(String version) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion(version)).load();
    }

    private static List<String> migrationHistory() {
        return jdbc.queryForList("""
                SELECT version || ':' || checksum || ':' || description
                FROM flyway_schema_history WHERE success = true ORDER BY installed_rank
                """, String.class);
    }

    private static List<String> columns(String table) {
        return jdbc.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """, String.class, table);
    }

    private static void assertIndex(String indexName, String columns) {
        assertThat(jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?
                """, String.class, indexName)).contains(columns);
    }

    private static Fixture fixture() {
        // A second fixture in the same test deliberately receives a distinct pair.
        long suffix = jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events", Long.class);
        long merchantId = insertMerchant("mrc_fixture_" + suffix);
        long endpointId = insertEndpoint("wep_fixture_" + suffix, merchantId);
        long eventId = insertEvent("evt_fixture_" + suffix, "evt_source_" + suffix, merchantId,
                "payment.succeeded", "PAYMENT_INTENT");
        return new Fixture(merchantId, eventId, endpointId);
    }

    private static long insertMerchant(String publicId) {
        return jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Webhook Merchant', 'ACTIVE', ?, ?) RETURNING id
                """, Long.class, publicId, NOW, NOW);
    }

    private static long insertEndpoint(String publicId, long merchantId) {
        return jdbc.queryForObject("""
                INSERT INTO webhook_endpoints (public_id, merchant_id, url, secret_ciphertext,
                    status, created_at, updated_at)
                VALUES (?, ?, 'https://merchant.example/webhooks', 'test-ciphertext', 'ACTIVE', ?, ?)
                RETURNING id
                """, Long.class, publicId, merchantId, NOW, NOW);
    }

    private static long insertEvent(String publicId, String sourceId, long merchantId,
                                    String eventType, String resourceType) {
        String resourceId = resourceType.equals("REFUND") ? "re_source" : "pi_source";
        return jdbc.queryForObject("""
                INSERT INTO webhook_events (public_id, source_event_id, merchant_id, event_type,
                    resource_type, resource_id, payload, occurred_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, jsonb_build_object('id', CAST(? AS text)), ?, ?) RETURNING id
                """, Long.class, publicId, sourceId, merchantId, eventType, resourceType, resourceId, publicId, NOW, NOW);
    }

    private static long insertDelivery(String publicId, Fixture fixture, String status,
                                       OffsetDateTime nextAttemptAt, OffsetDateTime leaseExpiresAt,
                                       OffsetDateTime deliveredAt) {
        return jdbc.queryForObject("""
                INSERT INTO webhook_deliveries (public_id, webhook_event_id, webhook_endpoint_id,
                    status, next_attempt_at, lease_expires_at, delivered_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, publicId, fixture.eventId(), fixture.endpointId(), status,
                nextAttemptAt, leaseExpiresAt, deliveredAt, NOW, NOW);
    }

    private static void insertAttempt(long deliveryId, int attemptNo, OffsetDateTime finishedAt,
                                      Integer httpStatus, Integer durationMs) {
        jdbc.update("""
                INSERT INTO webhook_delivery_attempts (delivery_id, attempt_no, started_at, finished_at,
                    http_status, duration_ms, error_message, created_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
                """, deliveryId, attemptNo, NOW, finishedAt, httpStatus, durationMs, NOW);
    }

    private static void assertSqlState(String expectedState, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception ->
                        assertThat(exception.getMostSpecificCause()).isInstanceOfSatisfying(
                                SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo(expectedState)));
    }

    private record Fixture(long merchantId, long eventId, long endpointId) {
    }
}
