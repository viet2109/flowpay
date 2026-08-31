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
        String requestHash = "a".repeat(64);
        String persistenceDetail =
                "duplicate key value violates unique constraint "
                        + "uq_idempotency_records_scope; idempotency_key="
                        + rawIdempotencyKey
                        + "; request_hash="
                        + requestHash
                        + "; merchant_id=41; id=91";
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
                .doesNotContain("uq_idempotency_records_scope")
                .doesNotContain(rawIdempotencyKey)
                .doesNotContain(requestHash)
                .doesNotContain("merchant_id")
                .doesNotContain("DataIntegrityViolationException");
        assertThat(output.getAll())
                .contains("Unhandled request failure")
                .doesNotContain(persistenceDetail)
                .doesNotContain("uq_idempotency_records_scope")
                .doesNotContain(rawIdempotencyKey)
                .doesNotContain(requestHash)
                .doesNotContain("DataIntegrityViolationException");
    }
}
