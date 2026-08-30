package com.flowpay.backend.identity.api;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.application.AuthSessionUseCase;
import com.flowpay.backend.identity.application.LoginCommand;
import com.flowpay.backend.identity.application.LoginResult;
import com.flowpay.backend.identity.application.RegistrationCommand;
import com.flowpay.backend.identity.application.RegistrationResult;
import com.flowpay.backend.identity.application.RegistrationUseCase;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AuthSessionApiTest {

    private static final String EMAIL = "viet@example.com";
    private static final String PASSWORD = "StrongPassword123!";
    private static final Pattern REFRESH_COOKIE = Pattern.compile("flowpay_refresh=([^;]+)");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RegistrationUseCase registrationUseCase;

    @Autowired
    private AuthSessionUseCase authSessionUseCase;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void cleanIdentityData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldLoginWithJwtAndHttpOnlyRefreshCookieWithoutPersistingRawToken() throws Exception {
        RegistrationResult registration = register();

        MvcResult result = login(PASSWORD)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.user.id").value(registration.user().publicId()))
                .andExpect(jsonPath("$.data.user.email").value(EMAIL))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Path=/api/v1/auth")))
                .andReturn();

        String rawRefreshToken = extractRefreshToken(result);
        String accessToken = result.getResponse().getContentAsString()
                .replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
        Jwt jwt = jwtDecoder.decode(accessToken);
        String persistedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_tokens",
                String.class
        );

        assertThat(jwt.getSubject()).isEqualTo(registration.user().publicId());
        assertThat(jwt.getClaimAsString("merchant")).isEqualTo(registration.merchant().publicId());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(persistedHash).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(rawRefreshToken);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(rawRefreshToken);
    }

    @Test
    void shouldReturnSameInvalidCredentialsForWrongPasswordAndUnknownEmail() throws Exception {
        register();

        login("wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The email or password is incorrect."));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("missing@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.detail").value("The email or password is incorrect."));

        assertThat(count("refresh_tokens")).isZero();
    }

    @Test
    void shouldRejectLockedAndDisabledUsersWithoutCreatingSessions() throws Exception {
        RegistrationResult registration = register();

        setUserStatus(registration.user().publicId(), "LOCKED");
        login(PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_LOCKED"));

        setUserStatus(registration.user().publicId(), "DISABLED");
        login(PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));

        assertThat(count("refresh_tokens")).isZero();
    }

    @Test
    void shouldRotateRefreshTokenAndRejectTheOldToken() throws Exception {
        register();
        String oldToken = extractRefreshToken(login(PASSWORD).andExpect(status().isOk()).andReturn());

        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("flowpay_refresh", oldToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.expiresIn").value(900))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();

        String replacement = extractRefreshToken(refresh);
        assertThat(replacement).isNotEqualTo(oldToken);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NOT NULL AND replaced_by_id IS NOT NULL",
                Integer.class
        )).isEqualTo(1);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("flowpay_refresh", oldToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));
    }

    @Test
    void shouldRejectMissingAndExpiredRefreshTokens() throws Exception {
        register();

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        String rawToken = extractRefreshToken(login(PASSWORD).andExpect(status().isOk()).andReturn());
        jdbcTemplate.update("""
                UPDATE refresh_tokens
                SET created_at = now() - interval '8 days',
                    expires_at = now() - interval '1 second'
                """);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("flowpay_refresh", rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
    }

    @Test
    void shouldLogoutRevokeSessionAndClearCookie() throws Exception {
        register();
        String rawToken = extractRefreshToken(login(PASSWORD).andExpect(status().isOk()).andReturn());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("flowpay_refresh", rawToken)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("flowpay_refresh=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens",
                Boolean.class
        )).isTrue();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("flowpay_refresh", rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldAllowOnlyOneConcurrentRotation() throws Exception {
        register();
        LoginResult login = authSessionUseCase.login(new LoginCommand(EMAIL, PASSWORD));
        String rawToken = login.refreshToken().value();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);

        List<Future<RefreshAttempt>> attempts = List.of(
                executor.submit(() -> refreshConcurrently(rawToken, ready, start)),
                executor.submit(() -> refreshConcurrently(rawToken, ready, start))
        );
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<RefreshAttempt> results = attempts.stream().map(future -> {
            try {
                return future.get(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }).toList();

        assertThat(results).filteredOn(RefreshAttempt::success).hasSize(1);
        assertThat(results).filteredOn(result -> result.errorCode() == ErrorCode.REFRESH_TOKEN_REVOKED).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class
        )).isEqualTo(1);
    }

    private RefreshAttempt refreshConcurrently(
            String rawToken,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await(10, TimeUnit.SECONDS);
        try {
            authSessionUseCase.refresh(rawToken);
            return new RefreshAttempt(true, null);
        } catch (ApiException exception) {
            return new RefreshAttempt(false, exception.code());
        }
    }

    private RegistrationResult register() {
        return registrationUseCase.register(new RegistrationCommand(
                EMAIL,
                PASSWORD,
                "Viet",
                "Nguyen",
                "ABC Store"
        ));
    }

    private org.springframework.test.web.servlet.ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-Request-Id", "req_login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(EMAIL, password)));
    }

    private static String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    private static String extractRefreshToken(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = REFRESH_COOKIE.matcher(setCookie == null ? "" : setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void setUserStatus(String publicId, String status) {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE public_id = ?", status, publicId);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private record RefreshAttempt(boolean success, ErrorCode errorCode) {
    }
}
