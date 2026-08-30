package com.flowpay.backend.infrastructure.security;

import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.error.ProblemDetailsFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public final class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemDetailsFactory problems;
    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        ErrorCode code = ErrorCode.AUTHENTICATION_REQUIRED;
        String detail = "A valid access token is required.";
        if (exception instanceof CodedAuthenticationException codedException) {
            code = codedException.code();
            detail = codedException.getMessage();
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(
                response,
                problems.create(
                        HttpStatus.UNAUTHORIZED,
                        code,
                        detail,
                        URI.create(request.getRequestURI())
                )
        );
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception
    ) throws IOException {
        write(
                response,
                problems.create(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.ACCESS_DENIED,
                        "The authenticated principal is not allowed to access this resource.",
                        URI.create(request.getRequestURI())
                )
        );
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
