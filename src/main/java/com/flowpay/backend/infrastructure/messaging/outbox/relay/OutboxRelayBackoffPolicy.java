package com.flowpay.backend.infrastructure.messaging.outbox.relay;

import com.flowpay.backend.infrastructure.messaging.FlowPayMessagingProperties;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;

@Component
public class OutboxRelayBackoffPolicy {

    private static final long MIN_DELAY_FACTOR_PERMILLE = 500L;
    private static final long DELAY_FACTOR_VARIANTS = 501L;
    private static final BigInteger NANOS_PER_SECOND =
            BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger PERMILLE = BigInteger.valueOf(1_000L);
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public OutboxRelayBackoffPolicy(FlowPayMessagingProperties properties) {
        FlowPayMessagingProperties.Relay relay = properties.outbox().relay();
        initialBackoff = relay.initialBackoff();
        maxBackoff = relay.maxBackoff();
    }

    public Duration delayAfterFailure(
            String eventId,
            int previousFailureCount
    ) {
        String stableEventId = requireText(eventId, "eventId");
        if (previousFailureCount < 0) {
            throw new IllegalArgumentException(
                    "previousFailureCount must not be negative"
            );
        }
        Duration exponentialCeiling = exponentialCeiling(previousFailureCount);
        long factorPermille = MIN_DELAY_FACTOR_PERMILLE + Long.remainderUnsigned(
                stableHash(stableEventId, previousFailureCount),
                DELAY_FACTOR_VARIANTS
        );
        return scale(exponentialCeiling, factorPermille);
    }

    private Duration exponentialCeiling(int previousFailureCount) {
        Duration delay = initialBackoff;
        for (int index = 0; index < previousFailureCount; index++) {
            if (delay.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maxBackoff;
            }
            if (delay.compareTo(maxBackoff) >= 0) {
                return maxBackoff;
            }
        }
        return delay;
    }

    private static Duration scale(Duration ceiling, long factorPermille) {
        BigInteger totalNanos = BigInteger.valueOf(ceiling.getSeconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(ceiling.getNano()));
        BigInteger scaledNanos = totalNanos
                .multiply(BigInteger.valueOf(factorPermille))
                .divide(PERMILLE)
                .max(BigInteger.ONE);
        BigInteger[] parts = scaledNanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(
                parts[0].longValueExact(),
                parts[1].longValueExact()
        );
    }

    private static long stableHash(String eventId, int previousFailureCount) {
        long hash = FNV_OFFSET_BASIS;
        for (int index = 0; index < eventId.length(); index++) {
            hash ^= eventId.charAt(index);
            hash *= FNV_PRIME;
        }
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash ^= (previousFailureCount >>> shift) & 0xffL;
            hash *= FNV_PRIME;
        }
        return hash;
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
