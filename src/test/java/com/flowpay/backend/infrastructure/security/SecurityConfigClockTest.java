package com.flowpay.backend.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigClockTest {

    private final Clock clock = new SecurityConfig().clock();

    @Test
    void shouldUseUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void shouldTickInWholeMicrosecondsLikePostgresTimestamps() {
        for (int i = 0; i < 10_000; i++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1_000).isZero();
        }
    }

    @Test
    void shouldNeverRunBehindItsPreviousReading() {
        Instant previous = clock.instant();
        for (int i = 0; i < 10_000; i++) {
            Instant now = clock.instant();
            assertThat(now).isAfterOrEqualTo(previous);
            previous = now;
        }
    }
}
