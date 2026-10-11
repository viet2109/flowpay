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
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class WebhookTraceContextMigrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Test
    void freshMigrationAddsOnlyBoundedNullableMetadata() {
        var flyway = flyway("fresh", "13");
        flyway.migrate();
        flyway.validate();
        var jdbc = jdbc("fresh");
        assertThat(jdbc.queryForList("SELECT version::integer FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", Integer.class))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 13).boxed().toList());
        assertThat(jdbc.queryForObject("""
                SELECT data_type || ':' || character_maximum_length || ':' || is_nullable
                    || ':' || COALESCE(column_default, '<none>') FROM information_schema.columns
                WHERE table_schema = 'fresh' AND table_name = 'webhook_events' AND column_name = 'traceparent'
                """, String.class)).isEqualTo("character varying:55:YES:<none>");
        legacy(jdbc, "one");
        assertThatThrownBy(() -> jdbc.update("UPDATE webhook_events SET traceparent = ?", "a".repeat(56)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update("UPDATE webhook_events SET traceparent = 'bad'")).isOne();
    }

    @Test
    void upgradePreservesAllChecksumsBusinessFactsConstraintsAndOldWriterCompatibility() {
        flyway("upgrade", "12").migrate();
        var jdbc = jdbc("upgrade");
        legacy(jdbc, "before");
        var history = history(jdbc);
        var rows = rows(jdbc);
        var constraints = constraints(jdbc);
        var migration = flyway("upgrade", "13");
        migration.migrate();
        migration.validate();
        assertThat(history(jdbc).subList(0, 12)).containsExactlyElementsOf(history);
        assertThat(rows(jdbc)).containsExactlyElementsOf(rows);
        assertThat(constraints(jdbc)).containsExactlyElementsOf(constraints);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE traceparent IS NULL", Long.class)).isOne();
        legacy(jdbc, "after");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_events WHERE traceparent IS NULL", Long.class)).isEqualTo(2);
    }

    private static void legacy(JdbcTemplate jdbc, String suffix) {
        long merchant = jdbc.queryForObject("""
                INSERT INTO merchants (public_id, name, status, created_at, updated_at)
                VALUES (?, 'Migration', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, "mrc_" + suffix);
        jdbc.update("""
                INSERT INTO webhook_events (public_id, source_event_id, merchant_id, event_type,
                    resource_type, resource_id, payload, occurred_at, created_at)
                VALUES (?, ?, ?, 'payment.succeeded', 'PAYMENT_INTENT', 'pi_legacy',
                    '{"id":"evt_legacy","data":{"amount":1000}}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "evt_" + suffix, "ievt_" + suffix, merchant);
    }

    private static List<String> rows(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT (to_jsonb(e) - 'traceparent')::text FROM webhook_events e ORDER BY id", String.class);
    }
    private static List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version || ':' || checksum FROM flyway_schema_history "
                + "WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class);
    }
    private static List<String> constraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'webhook_events'::regclass ORDER BY conname", String.class);
    }
    private static Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).target(target).load();
    }
    private static JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
