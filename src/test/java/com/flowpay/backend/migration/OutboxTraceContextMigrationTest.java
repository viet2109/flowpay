package com.flowpay.backend.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class OutboxTraceContextMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Test
    void shouldApplyFreshSchemaWithBoundedNullableTechnicalColumnOnly() {
        Flyway migration = flyway("fresh", "12");
        migration.migrate();
        migration.validate();
        JdbcTemplate jdbc = jdbc("fresh");
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", Integer.class))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 12).boxed().toList());
        assertThat(jdbc.queryForObject("""
                SELECT data_type || ':' || character_maximum_length || ':' || is_nullable
                    || ':' || COALESCE(column_default, '<none>')
                FROM information_schema.columns
                WHERE table_schema = 'fresh' AND table_name = 'outbox_events'
                    AND column_name = 'traceparent'
                """, String.class)).isEqualTo("character varying:55:YES:<none>");
        insertLegacy(jdbc, "ievt_length");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE outbox_events SET traceparent = ?", "a".repeat(56)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Bounded bad metadata remains readable and is ignored by infrastructure.
        assertThat(jdbc.update("UPDATE outbox_events SET traceparent = 'malformed'"))
                .isEqualTo(1);
    }

    @Test
    void shouldUpgradeWithoutChangingChecksumsBusinessRowsOrLegacyWriterCompatibility() {
        flyway("upgrade", "11").migrate();
        JdbcTemplate jdbc = jdbc("upgrade");
        List<String> history = history(jdbc);
        insertLegacy(jdbc, "ievt_upgrade");
        List<String> before = businessRows(jdbc);
        List<String> constraints = constraints(jdbc);
        Flyway current = flyway("upgrade", "12");
        current.migrate();
        current.validate();
        assertThat(history(jdbc).subList(0, 11)).containsExactlyElementsOf(history);
        assertThat(businessRows(jdbc)).containsExactlyElementsOf(before);
        assertThat(constraints(jdbc)).containsExactlyElementsOf(constraints);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events "
                + "WHERE traceparent IS NULL", Long.class)).isEqualTo(1L);
        insertLegacy(jdbc, "ievt_old_writer_after_upgrade");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events "
                + "WHERE traceparent IS NULL", Long.class)).isEqualTo(2L);
        String parent = "00-" + "a".repeat(32) + "-" + "b".repeat(16) + "-00";
        jdbc.update("UPDATE outbox_events SET traceparent = ? WHERE event_id = 'ievt_upgrade'", parent);
        assertThat(businessRows(jdbc).getFirst()).isEqualTo(before.getFirst());
        assertThat(jdbc.queryForObject("SELECT traceparent FROM outbox_events "
                + "WHERE event_id = 'ievt_upgrade'", String.class)).isEqualTo(parent);
    }

    private static Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(target).load();
    }

    private static JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private static List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version || ':' || checksum || ':' || description "
                + "FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
    }

    private static List<String> businessRows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT (to_jsonb(o) - 'traceparent')::text "
                + "FROM outbox_events o ORDER BY id", String.class);
    }

    private static List<String> constraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT conname || ':' || pg_get_constraintdef(oid) "
                + "FROM pg_constraint WHERE conrelid = 'outbox_events'::regclass ORDER BY conname",
                String.class);
    }

    private static void insertLegacy(JdbcTemplate jdbc, String eventId) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type,
                    payload, status, occurred_at, available_at, created_at)
                VALUES (?, 'PAYMENT_INTENT', 'pi_legacy', 'payment.succeeded.v1',
                    '{"amountMinor":1000}', 'PENDING', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, eventId);
    }
}
