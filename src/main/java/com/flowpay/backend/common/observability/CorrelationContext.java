package com.flowpay.backend.common.observability;

import org.slf4j.MDC;

public final class CorrelationContext {

    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private CorrelationContext() {
    }

    public static String currentRequestId() {
        return MDC.get(REQUEST_ID_MDC_KEY);
    }
}
