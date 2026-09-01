package com.flowpay.backend.refund.application;

import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.refund.domain.RefundReason;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateRefundFingerprintTest {

    private final RequestFingerprintService fingerprintService =
            new RequestFingerprintService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldProduceStableLowercaseSha256ForEquivalentRefundRequests() {
        CreateRefundFingerprint first = CreateRefundFingerprint.version1(
                " pi_refund_1001 ",
                50_000L,
                RefundReason.of(" Customer request ")
        );
        CreateRefundFingerprint equivalent = CreateRefundFingerprint.version1(
                "pi_refund_1001",
                50_000L,
                RefundReason.of("Customer request")
        );

        assertThat(fingerprintService.fingerprint(first))
                .isEqualTo(fingerprintService.fingerprint(equivalent))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shouldTreatBlankAndNullReasonAsTheSameSemanticValue() {
        String blank = fingerprintService.fingerprint(CreateRefundFingerprint.version1(
                "pi_refund_1001",
                50_000L,
                RefundReason.of(" \t ")
        ));
        String absent = fingerprintService.fingerprint(CreateRefundFingerprint.version1(
                "pi_refund_1001",
                50_000L,
                RefundReason.of(null)
        ));

        assertThat(blank).isEqualTo(absent);
    }

    @Test
    void shouldChangeHashWhenAnyRefundSemanticFieldChanges() {
        String baseline = fingerprintService.fingerprint(CreateRefundFingerprint.version1(
                "pi_refund_1001",
                50_000L,
                RefundReason.of("Customer request")
        ));
        String changedPayment = fingerprintService.fingerprint(
                CreateRefundFingerprint.version1(
                        "pi_refund_1002",
                        50_000L,
                        RefundReason.of("Customer request")
                )
        );
        String changedAmount = fingerprintService.fingerprint(
                CreateRefundFingerprint.version1(
                        "pi_refund_1001",
                        50_001L,
                        RefundReason.of("Customer request")
                )
        );
        String changedReason = fingerprintService.fingerprint(
                CreateRefundFingerprint.version1(
                        "pi_refund_1001",
                        50_000L,
                        RefundReason.of("Duplicate order")
                )
        );

        assertThat(Set.of(baseline, changedPayment, changedAmount, changedReason))
                .hasSize(4);
    }

    @Test
    void shouldNotDependOnJsonPropertyOrder() throws Exception {
        String firstJson = """
                {
                  "version": 1,
                  "paymentPublicId": "pi_refund_1001",
                  "amountMinor": 50000,
                  "reason": {"value": "Customer request"}
                }
                """;
        String reorderedJson = """
                {
                  "reason": {"value": "Customer request"},
                  "amountMinor": 50000,
                  "paymentPublicId": "pi_refund_1001",
                  "version": 1
                }
                """;

        CreateRefundFingerprint first = objectMapper.readValue(
                firstJson,
                CreateRefundFingerprint.class
        );
        CreateRefundFingerprint reordered = objectMapper.readValue(
                reorderedJson,
                CreateRefundFingerprint.class
        );

        assertThat(fingerprintService.fingerprint(first))
                .isEqualTo(fingerprintService.fingerprint(reordered));
    }

    @Test
    void shouldUseRefundReasonAsTheSingleNormalizationOwner() {
        assertThatThrownBy(() -> CreateRefundFingerprint.version1(
                "pi_refund_1001",
                50_000L,
                RefundReason.of("x".repeat(RefundReason.MAX_LENGTH + 1))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must not exceed 255 characters");
        assertThatThrownBy(() -> new CreateRefundFingerprint(
                2,
                "pi_refund_1001",
                50_000L,
                RefundReason.of(null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("create refund fingerprint version must be 1");
        assertThatThrownBy(() -> CreateRefundFingerprint.version1(
                "pi_refund_1001",
                0L,
                RefundReason.of(null)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
    }

    @Test
    void shouldExcludeScopeAndClientCurrencyFromFingerprintInput() {
        assertThat(Arrays.stream(CreateRefundFingerprint.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("version", "paymentPublicId", "amountMinor", "reason")
                .doesNotContain("merchantId", "idempotencyKey", "currency");
    }
}
