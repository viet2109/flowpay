package com.flowpay.backend.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ProblemDetailsFactory());

    @Test
    void shouldNotExposePersistenceDetailsInUnexpectedErrorResponse() {
        String persistenceDetail =
                "duplicate key value violates unique constraint uq_payment_transactions_intent_attempt";
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
                .doesNotContain("uq_payment_transactions_intent_attempt")
                .doesNotContain("DataIntegrityViolationException");
    }
}
