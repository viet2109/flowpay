package com.flowpay.backend.idempotency.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestFingerprintServiceTest {

    private final RequestFingerprintService service = new RequestFingerprintService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldProduceStableLowercaseSha256ForEquivalentCreateRequests() {
        CreatePaymentFingerprint first = CreatePaymentFingerprint.version1(
                50_000L,
                "usd",
                " ORDER-1001 ",
                " Checkout payment "
        );
        CreatePaymentFingerprint equivalent = CreatePaymentFingerprint.version1(
                50_000L,
                " USD ",
                "ORDER-1001",
                "Checkout payment"
        );

        String firstHash = service.fingerprint(first);
        String equivalentHash = service.fingerprint(equivalent);

        assertThat(firstHash)
                .isEqualTo(equivalentHash)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void shouldNotDependOnJsonPropertyOrder() throws Exception {
        String firstJson = """
                {
                  "version": 1,
                  "amountMinor": 50000,
                  "currency": "USD",
                  "orderId": "ORDER-1001",
                  "description": "Checkout payment"
                }
                """;
        String reorderedJson = """
                {
                  "description": "Checkout payment",
                  "orderId": "ORDER-1001",
                  "currency": "USD",
                  "amountMinor": 50000,
                  "version": 1
                }
                """;

        CreatePaymentFingerprint first = objectMapper.readValue(
                firstJson,
                CreatePaymentFingerprint.class
        );
        CreatePaymentFingerprint reordered = objectMapper.readValue(
                reorderedJson,
                CreatePaymentFingerprint.class
        );

        assertThat(service.fingerprint(first)).isEqualTo(service.fingerprint(reordered));
    }

    @Test
    void shouldChangeCreateHashWhenAnySemanticFieldChanges() {
        String baseline = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "USD",
                "ORDER-1001",
                "Checkout payment"
        ));
        String changedAmount = service.fingerprint(CreatePaymentFingerprint.version1(
                50_001L,
                "USD",
                "ORDER-1001",
                "Checkout payment"
        ));
        String changedCurrency = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "EUR",
                "ORDER-1001",
                "Checkout payment"
        ));
        String changedOrderId = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "USD",
                "ORDER-1002",
                "Checkout payment"
        ));
        String changedDescription = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "USD",
                "ORDER-1001",
                "Updated payment"
        ));

        assertThat(Set.of(
                baseline,
                changedAmount,
                changedCurrency,
                changedOrderId,
                changedDescription
        )).hasSize(5);
    }

    @Test
    void shouldDistinguishNullTextFromPresentTextWithoutDelimiterCollisions() {
        String nullOrder = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "USD",
                null,
                "line one\norderId=4:test"
        ));
        String presentOrder = service.fingerprint(CreatePaymentFingerprint.version1(
                50_000L,
                "USD",
                "test",
                "line one"
        ));

        assertThat(nullOrder).isNotEqualTo(presentOrder);
    }

    @Test
    void shouldFingerprintConfirmByVersionAndNormalizedPaymentPublicId() {
        String first = service.fingerprint(ConfirmPaymentFingerprint.version1(" pi_1001 "));
        String equivalent = service.fingerprint(ConfirmPaymentFingerprint.version1("pi_1001"));
        String differentPayment = service.fingerprint(
                ConfirmPaymentFingerprint.version1("pi_1002")
        );

        assertThat(first)
                .isEqualTo(equivalent)
                .matches("[0-9a-f]{64}");
        assertThat(differentPayment).isNotEqualTo(first);
    }

    @Test
    void shouldRejectUnsupportedFingerprintVersionsAndInvalidSemanticInputs() {
        assertThatThrownBy(() -> new CreatePaymentFingerprint(
                2,
                50_000L,
                "USD",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("create payment fingerprint version must be 1");
        assertThatThrownBy(() -> CreatePaymentFingerprint.version1(
                0L,
                "USD",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");
        assertThatThrownBy(() -> new ConfirmPaymentFingerprint(2, "pi_1001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("confirm payment fingerprint version must be 1");
        assertThatThrownBy(() -> ConfirmPaymentFingerprint.version1(" \t "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("paymentPublicId must not be blank");
    }
}
