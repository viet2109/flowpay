package com.flowpay.backend.common.error;

import com.flowpay.backend.common.observability.CorrelationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

@Component
public class ProblemDetailsFactory {

    private static final String TYPE_BASE = "https://flowpay.dev/problems/";

    public ProblemDetail create(HttpStatus status, ErrorCode code, String detail, URI instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(code.title());
        problem.setType(URI.create(TYPE_BASE + code.slug()));
        problem.setInstance(instance);
        problem.setProperty("code", code.name());

        String requestId = CorrelationContext.currentRequestId();
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
        return problem;
    }

    public ProblemDetail validation(String detail, URI instance, List<FieldViolation> errors) {
        ProblemDetail problem = create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, detail, instance);
        problem.setProperty("errors", List.copyOf(errors));
        return problem;
    }
}
