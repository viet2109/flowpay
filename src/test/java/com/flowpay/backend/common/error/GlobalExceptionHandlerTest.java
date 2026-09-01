package com.flowpay.backend.common.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ProblemDetailsFactory());

    @Test
    void shouldNotExposePersistenceDetailsInUnexpectedErrorResponseOrLogs(
            CapturedOutput output
    ) {
        String rawIdempotencyKey = "checkout-secret-idempotency-key";
        String rawApiKey = "fp_live_secret-api-key-material";
        String requestHash = "a".repeat(64);
        String persistenceDetail =
                "duplicate key value violates unique constraint "
                        + "uq_ledger_transactions_business_reference; related_constraint="
                        + "uq_idempotency_records_scope; idempotency_key="
                        + rawIdempotencyKey
                        + "; api_key="
                        + rawApiKey
                        + "; request_hash="
                        + requestHash
                        + "; account_code=MERCHANT_PAYABLE:41:VND"
                        + "; ledger_public_id=ltxn_sensitive; merchant_id=41; id=91";
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/payment-intents/pi_safe"
        );

        ProblemDetail problem = handler.handleUnexpected(
                new DataIntegrityViolationException(persistenceDetail),
                request
        );

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred.");
        assertThat(problem.getProperties()).containsEntry("code", ErrorCode.INTERNAL_ERROR.name());
        assertThat(problem.toString())
                .doesNotContain(persistenceDetail)
                .doesNotContain("uq_ledger_transactions_business_reference")
                .doesNotContain("uq_idempotency_records_scope")
                .doesNotContain(rawIdempotencyKey)
                .doesNotContain(rawApiKey)
                .doesNotContain(requestHash)
                .doesNotContain("MERCHANT_PAYABLE:41:VND")
                .doesNotContain("ltxn_sensitive")
                .doesNotContain("merchant_id")
                .doesNotContain("DataIntegrityViolationException");
        assertThat(output.getAll())
                .contains("Unhandled request failure")
                .doesNotContain(persistenceDetail)
                .doesNotContain("uq_ledger_transactions_business_reference")
                .doesNotContain("uq_idempotency_records_scope")
                .doesNotContain(rawIdempotencyKey)
                .doesNotContain(rawApiKey)
                .doesNotContain(requestHash)
                .doesNotContain("MERCHANT_PAYABLE:41:VND")
                .doesNotContain("ltxn_sensitive")
                .doesNotContain("DataIntegrityViolationException");
    }
}
