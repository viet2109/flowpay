package com.flowpay.backend.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.io.IOException;

/** Browser form requests cannot supply this header; credentialed cross-origin CORS stays closed. */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class AuthRequestMarkerFilter extends OncePerRequestFilter {
    static final String HEADER = "X-FlowPay-CSRF";
    private static final PathPatternRequestMatcher AUTH_PATH =
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");
    private final SecurityProblemHandler problems;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Match decoded path segments like Security/MVC, including servlet context and encoded letters.
        return !AUTH_PATH.matches(request)
                || !("POST".equals(request.getMethod()) || CorsUtils.isPreFlightRequest(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var values = request.getHeaders(HEADER);
        // No reflected Origin/Host/Forwarded allowlist. Never permit cross-origin auth preflight.
        if (CorsUtils.isPreFlightRequest(request) || !values.hasMoreElements()
                || !"1".equals(values.nextElement()) || values.hasMoreElements()) {
            problems.rejectAuthRequest(request, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
