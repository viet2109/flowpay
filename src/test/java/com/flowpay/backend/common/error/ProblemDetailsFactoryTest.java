package com.flowpay.backend.common.error;

import com.flowpay.backend.common.observability.CorrelationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemDetailsFactoryTest {

    private final ProblemDetailsFactory factory = new ProblemDetailsFactory();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void shouldIncludeStableErrorCodeAndRequestId() {
        MDC.put(CorrelationContext.REQUEST_ID_MDC_KEY, "req_test");

        var problem = factory.create(
                HttpStatus.BAD_REQUEST,
                ErrorCode.MALFORMED_REQUEST,
                "Bad payload",
                URI.create("/api/v1/test")
        );

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getProperties())
                .containsEntry("code", "MALFORMED_REQUEST")
                .containsEntry("requestId", "req_test");
    }
}
