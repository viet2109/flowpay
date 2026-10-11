package com.flowpay.backend.webhook.infrastructure.persistence;

import com.flowpay.backend.common.observability.AsyncWorkTracing;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurableWebhookWorkerTracingTest {
    @Test
    void failedMetadataLookupCannotSuppressAttemptRecoveryOrStaleResult() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(PreparedStatementCreator.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("synthetic-private"));
        var tracing = new DurableWebhookWorkerTracing(jdbc, new AsyncWorkTracing(OpenTelemetry.noop()));
        var calls = new AtomicInteger();
        assertThat(tracing.attempt(1, () -> { calls.incrementAndGet(); return true; })).isTrue();
        assertThat(tracing.recovery(1, () -> { calls.incrementAndGet(); return false; })).isFalse();
        assertThat(calls).hasValue(2);
        var original = new IllegalStateException("synthetic-private");
        assertThatThrownBy(() -> tracing.attempt(1, () -> { calls.incrementAndGet(); throw original; })).isSameAs(original);
        assertThat(calls).hasValue(3);
    }
}
