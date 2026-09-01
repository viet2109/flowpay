package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.RefundStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundResponseSnapshotTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void shouldExposeFrozenKnownAndPendingHttpSemantics() {
        RefundResponseSnapshot succeeded = snapshot(
                RefundStatus.SUCCEEDED,
                "provider_refund_1",
                null,
                null,
                CREATED_AT.plusSeconds(1)
        );
        RefundResponseSnapshot failed = snapshot(
                RefundStatus.FAILED,
                null,
                "REFUND_DECLINED",
                "The provider declined the refund.",
                CREATED_AT.plusSeconds(1)
        );
        RefundResponseSnapshot unknown = snapshot(
                RefundStatus.PROCESSING,
                null,
                "PROVIDER_TIMEOUT",
                "The provider outcome is unknown.",
                null
        );

        assertThat(succeeded.httpStatus()).isEqualTo(201);
        assertThat(failed.httpStatus()).isEqualTo(201);
        assertThat(unknown.httpStatus()).isEqualTo(202);
    }

    @Test
    void shouldRejectSnapshotsThatCouldMisrepresentRefundState() {
        assertThatThrownBy(() -> snapshot(
                RefundStatus.SUCCEEDED,
                null,
                null,
                null,
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                RefundStatus.FAILED,
                null,
                null,
                null,
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                RefundStatus.PROCESSING,
                null,
                "PROVIDER_TIMEOUT",
                "The provider outcome is unknown.",
                CREATED_AT.plusSeconds(1)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> snapshot(
                RefundStatus.CREATED,
                null,
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static RefundResponseSnapshot snapshot(
            RefundStatus status,
            String providerRefundId,
            String failureCode,
            String failureMessage,
            Instant completedAt
    ) {
        return new RefundResponseSnapshot(
                "re_snapshot",
                "pi_snapshot",
                10_000L,
                "VND",
                status,
                null,
                "SIMULATOR",
                providerRefundId,
                failureCode,
                failureMessage,
                CREATED_AT,
                CREATED_AT.plusSeconds(1),
                completedAt
        );
    }
}
