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
