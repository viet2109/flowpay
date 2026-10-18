package com.flowpay.backend.web;

import com.flowpay.backend.infrastructure.web.RequestCorrelationFilter;
import com.flowpay.backend.infrastructure.web.RequestIdGenerator;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTest {

    @Test
    void shouldRestoreOuterContextEvenWhenChainThrows() {
        MDC.put(RequestCorrelationFilter.MDC_KEY, "outer-request");
        try {
            var failure = new jakarta.servlet.ServletException("synthetic-private-message");
            var filter = new RequestCorrelationFilter(new RequestIdGenerator());
            var request = new MockHttpServletRequest();
            request.addHeader(RequestCorrelationFilter.HEADER_NAME, "nested-request");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request,
                    new MockHttpServletResponse(), (req, res) -> {
                        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("nested-request");
                        throw failure;
                    })).isSameAs(failure);
            assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isEqualTo("outer-request");
        } finally {
            MDC.remove(RequestCorrelationFilter.MDC_KEY);
        }
    }

    @Test
    void shouldPropagateSafeClientRequestIdAndClearMdc() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new RequestIdGenerator());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringChain = new AtomicReference<>();
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "client-request-123");

        filter.doFilter(request, response, (req, res) -> duringChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY)));

        assertThat(duringChain.get()).isEqualTo("client-request-123");
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME)).isEqualTo("client-request-123");
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void shouldGenerateRequestIdWhenHeaderIsUnsafe() throws Exception {
        RequestCorrelationFilter filter = new RequestCorrelationFilter(new RequestIdGenerator());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(RequestCorrelationFilter.HEADER_NAME, "bad header\\nvalue");

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME)).startsWith("req_");
    }
}
