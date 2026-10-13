package com.flowpay.backend.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.Mockito.*;

class AuthRequestMarkerFilterTest {
    private final SecurityProblemHandler problems = mock(SecurityProblemHandler.class);
    private final AuthRequestMarkerFilter filter = new AuthRequestMarkerFilter(problems);
    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void rejectsMissingMarkerInsideServletContextWithoutDownstreamWork() throws Exception {
        var request = new MockHttpServletRequest("POST", "/backend/api/v1/auth/login");
        request.setContextPath("/backend");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(problems).rejectAuthRequest(request, response);
        verifyNoInteractions(chain);
    }

    @Test
    void passesExactlyOneMarkerAndKeepsScopeOffBearerApisAndReadOnlyRequests() throws Exception {
        for (var request : new MockHttpServletRequest[]{
                new MockHttpServletRequest("POST", "/api/v1/auth/login"),
                new MockHttpServletRequest("POST", "/api/v1/payment-intents"),
                new MockHttpServletRequest("GET", "/api/v1/auth/login")}) {
            if (request.getRequestURI().endsWith("login") && request.getMethod().equals("POST")) {
                request.addHeader(AuthRequestMarkerFilter.HEADER, "1");
            }
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
        verifyNoInteractions(problems);
    }

    @Test
    void refusesEveryAuthCorsPreflightEvenWithMarker() throws Exception {
        var request = new MockHttpServletRequest("OPTIONS", "/api/v1/auth/login");
        request.addHeader("Origin", "https://foreign.example");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader(AuthRequestMarkerFilter.HEADER, "1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(problems).rejectAuthRequest(request, response);
        verifyNoInteractions(chain);
    }

    @Test
    void matchesEncodedAuthSegmentsLikeMvcInsteadOfRawStringPrefix() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/%61uth/lo%67in");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        verify(problems).rejectAuthRequest(request, response);
        verifyNoInteractions(chain);
    }
}
