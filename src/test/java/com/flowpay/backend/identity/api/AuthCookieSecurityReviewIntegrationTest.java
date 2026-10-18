package com.flowpay.backend.identity.api;

import com.flowpay.backend.identity.application.AuthSessionUseCase;
import com.flowpay.backend.identity.application.LoginCommand;
import com.flowpay.backend.identity.application.RegistrationCommand;
import com.flowpay.backend.identity.application.RegistrationUseCase;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual cookie/session wiring; never dump credential-bearing MockMvc requests or responses. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AuthCookieSecurityReviewIntegrationTest extends PostgresIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private RegistrationUseCase registration;
    @Autowired private AuthSessionUseCase sessions;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(strings = {"refresh", "logout"})
    void shouldRejectSameSiteForeignOriginFormPostBeforeRotatingOrRevoking(String operation) throws Exception {
        String email = "csrf-review-" + UUID.randomUUID() + "@example.com";
        String password = "FixturePassword123!";
        registration.register(new RegistrationCommand(email, password, "Review", "Fixture", "Review Merchant"));
        String token = sessions.login(new LoginCommand(email, password)).refreshToken().value();
        long revokedBefore = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Long.class);
        long totalBefore = jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class);

        // A same-site sibling can form POST to the target host; it need not read its host-only cookie.
        var result = mvc.perform(post("/api/v1/auth/" + operation)
                .secure(true)
                .with(request -> { request.setServerName("api.flowpay.test"); return request; })
                .header("Origin", "https://untrusted.flowpay.test")
                .header("Sec-Fetch-Site", "same-site")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .cookie(new Cookie("flowpay_refresh", token))).andReturn();

        // Do not print the MockMvc request/response: it contains synthetic session credentials.
        assertThat(result.getResponse().getStatus()).as("Foreign-origin cookie form POST must be denied").isEqualTo(403);
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Long.class)).isEqualTo(totalBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL", Long.class))
                .isEqualTo(revokedBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void shouldRejectAbsentOrInvalidMarkersBeforeParsingBodyOrCredentials(String operation) throws Exception {
        for (String marker : new String[]{"", "0", "true", "1,1", " 1", "1 "}) {
            mvc.perform(post("/api/v1/auth/" + operation).header("X-FlowPay-CSRF", marker)
                    .header("X-Request-Id", "req_auth_rejection")
                    .header("Authorization", "Bearer invalid-fixture")
                    .contentType(MediaType.APPLICATION_JSON).content("not-json"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andExpect(jsonPath("$.requestId").value("req_auth_rejection"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(post("/api/v1/auth/" + operation).contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post("/api/v1/auth/" + operation).header("X-FlowPay-CSRF", "1", "1"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.flowpay.test", "https://foreign.example", "null"})
    void shouldDenyAuthPreflightWithoutReflectedCorsOrForwardedHeaderTrust(String origin) throws Exception {
        mvc.perform(options("/api/v1/auth/refresh")
                .header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-FlowPay-CSRF")
                .header("X-FlowPay-CSRF", "1")
                .header("Forwarded", "host=trusted.flowpay.test;proto=https;for=127.0.0.1")
                .header("X-Forwarded-For", "127.0.0.1"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void shouldKeepValidMarkerCookieRotationAndLogoutWithoutOriginOrHostTrust() throws Exception {
        String email = "csrf-valid-" + UUID.randomUUID() + "@example.com";
        String password = "FixturePassword123!";
        registration.register(new RegistrationCommand(email, password, "Review", "Fixture", "Valid Merchant"));
        String token = sessions.login(new LoginCommand(email, password)).refreshToken().value();
        // Non-browser clients have no Origin. The marker is required, not an identity credential.
        var refreshed = mvc.perform(post("/api/v1/auth/refresh").header("X-FlowPay-CSRF", "1")
                .cookie(new Cookie("flowpay_refresh", token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist()).andReturn();
        String replacement = refreshed.getResponse().getHeader("Set-Cookie").split(";", 2)[0].split("=", 2)[1];
        assertThat(replacement.equals(token)).as("Refresh must rotate the token").isFalse();
        mvc.perform(post("/api/v1/auth/logout").header("X-FlowPay-CSRF", "1")
                .cookie(new Cookie("flowpay_refresh", replacement)))
                .andExpect(status().isNoContent()).andExpect(header().exists("Set-Cookie"));
        mvc.perform(post("/api/v1/auth/refresh").header("X-FlowPay-CSRF", "1")
                .cookie(new Cookie("flowpay_refresh", replacement)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void shouldNotRequireMarkerForBearerFinancialAuthenticationOrPermitCorsPreflight() throws Exception {
        mvc.perform(post("/api/v1/payment-intents").header("Authorization", "Bearer invalid-fixture"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
        var response = mvc.perform(options("/api/v1/payment-intents").header("Origin", "https://foreign.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Idempotency-Key"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isIn(401, 403);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/%61uth/login", "/api/v1/auth/lo%67in"})
    void shouldNotBypassMarkerViaEncodedPathLetters(String path) throws Exception {
        mvc.perform(post(URI.create(path)).contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
