package com.flowpay.backend;

import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class PhaseOneEndToEndTest {

    private static final String PASSWORD = "StrongPassword123!";
    private static final String PAYMENT_PATH = "/api/v1/payment-intents";
    private static final Pattern REFRESH_COOKIE = Pattern.compile("flowpay_refresh=([^;]+)");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MerchantAccessApi merchantAccessApi;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldCompletePhaseOneLifecycleAcrossPublicDashboardAndIntegrationApis(CapturedOutput output)
            throws Exception {
        Registration registration = register(" owner@flowpay.dev ", "Original Store");
        Session login = login("owner@flowpay.dev");

        assertThat(registration.normalizedEmail()).isEqualTo("owner@flowpay.dev");
        assertRegistrationPersistence(registration);
        assertMerchantAccessContract(registration);
        assertDashboardJwt(login.accessToken(), registration);
        assertRefreshTokenIsHashed(login.refreshToken());

        mockMvc.perform(get("/api/v1/merchant").header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(registration.merchantPublicId()))
                .andExpect(jsonPath("$.data.name").value("Original Store"))
                .andExpect(jsonPath("$.data.internalId").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist());

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Updated Store \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(registration.merchantPublicId()))
                .andExpect(jsonPath("$.data.name").value("Updated Store"));

        CreatedKey apiKey = createApiKey(login.accessToken(), "Production backend");
        assertApiKeyIsHashed(apiKey);

        mockMvc.perform(get("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(apiKey.publicId()))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].key").doesNotExist())
                .andExpect(jsonPath("$.data[0].keyHash").doesNotExist());

        assertIntegrationAuthenticationSucceeds(apiKey.rawKey());

        Session refreshed = refresh(login.refreshToken());
        assertDashboardJwt(refreshed.accessToken(), registration);
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie(login.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));

        mockMvc.perform(get("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(refreshed.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Updated Store"));

        mockMvc.perform(delete("/api/v1/merchant/api-keys/{keyId}", apiKey.publicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(refreshed.accessToken())))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get(PAYMENT_PATH)
                        .header(HttpHeaders.AUTHORIZATION, bearer(apiKey.rawKey())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(apiKey.rawKey())
                )));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(refreshCookie(refreshed.refreshToken())))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie(refreshed.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REVOKED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM merchant_api_keys WHERE public_id = ?",
                String.class,
                apiKey.publicId()
        )).isEqualTo("REVOKED");
        assertThat(output.getAll())
                .doesNotContain(login.refreshToken())
                .doesNotContain(refreshed.refreshToken())
                .doesNotContain(apiKey.rawKey());
    }

    @Test
    void shouldKeepMerchantAndApiKeyOwnershipIsolatedAcrossRegisteredAccounts() throws Exception {
        Registration owner = register("owner@flowpay.dev", "Owner Store");
        Registration other = register("other@flowpay.dev", "Other Store");
        Session ownerSession = login("owner@flowpay.dev");
        Session otherSession = login("other@flowpay.dev");
        CreatedKey otherKey = createApiKey(otherSession.accessToken(), "Other backend");

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerSession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Owner Store Updated",
                                  "merchantId": "%s"
                                }
                                """.formatted(other.merchantPublicId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(owner.merchantPublicId()))
                .andExpect(jsonPath("$.data.name").value("Owner Store Updated"));

        mockMvc.perform(delete("/api/v1/merchant/api-keys/{keyId}", otherKey.publicId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerSession.accessToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(other.merchantPublicId()))
                .andExpect(jsonPath("$.data.name").value("Other Store"));
        mockMvc.perform(get("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(otherKey.publicId()))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        assertIntegrationAuthenticationSucceeds(otherKey.rawKey());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM merchants WHERE public_id = ?",
                String.class,
                other.merchantPublicId()
        )).isEqualTo("Other Store");
    }

    private Registration register(String email, String merchantName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "firstName": "Flow",
                                  "lastName": "Owner",
                                  "merchantName": "%s"
                                }
                                """.formatted(email, PASSWORD, merchantName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.id").isString())
                .andExpect(jsonPath("$.data.merchant.id").isString())
                .andReturn();
        JsonNode data = responseData(result);
        return new Registration(
                data.path("user").path("id").stringValue(),
                data.path("user").path("email").stringValue(),
                data.path("merchant").path("id").stringValue()
        );
    }

    private Session login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        return new Session(
                responseData(result).path("accessToken").stringValue(),
                extractRefreshToken(result)
        );
    }

    private Session refresh(String rawRefreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refreshCookie(rawRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andReturn();
        return new Session(
                responseData(result).path("accessToken").stringValue(),
                extractRefreshToken(result)
        );
    }

    private CreatedKey createApiKey(String accessToken, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.key").isString())
                .andExpect(jsonPath("$.data.keyHash").doesNotExist())
                .andReturn();
        JsonNode data = responseData(result);
        return new CreatedKey(
                data.path("id").stringValue(),
                data.path("key").stringValue()
        );
    }

    private void assertIntegrationAuthenticationSucceeds(String rawApiKey) throws Exception {
        mockMvc.perform(get(PAYMENT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(rawApiKey)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void assertRegistrationPersistence(Registration registration) {
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT count(*)
                        FROM merchant_members mm
                        JOIN users u ON u.id = mm.user_id
                        JOIN merchants m ON m.id = mm.merchant_id
                        WHERE u.public_id = ? AND m.public_id = ? AND mm.role = 'OWNER'
                        """,
                Integer.class,
                registration.userPublicId(),
                registration.merchantPublicId()
        )).isEqualTo(1);
        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE public_id = ?",
                String.class,
                registration.userPublicId()
        );
        assertThat(passwordHash).startsWith("$2").doesNotContain(PASSWORD);
    }

    private void assertDashboardJwt(String rawAccessToken, Registration registration) {
        Jwt jwt = jwtDecoder.decode(rawAccessToken);
        assertThat(jwt.getSubject()).isEqualTo(registration.userPublicId());
        assertThat(jwt.getClaimAsString("merchant")).isEqualTo(registration.merchantPublicId());
        assertThat(jwt.getClaimAsString("role")).isEqualTo("OWNER");
        assertThat(jwt.getClaims()).doesNotContainKeys("userId", "merchantId", "internalId");
    }

    private void assertMerchantAccessContract(Registration registration) {
        ActiveMerchantSnapshot snapshot = merchantAccessApi.requireActiveMerchant(registration.merchantPublicId());
        Long expectedInternalId = jdbcTemplate.queryForObject(
                "SELECT id FROM merchants WHERE public_id = ?",
                Long.class,
                registration.merchantPublicId()
        );
        assertThat(snapshot).isEqualTo(new ActiveMerchantSnapshot(
                expectedInternalId,
                registration.merchantPublicId()
        ));
    }

    private void assertRefreshTokenIsHashed(String rawRefreshToken) {
        String digest = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM refresh_tokens WHERE revoked_at IS NULL",
                String.class
        );
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(rawRefreshToken);
    }

    private void assertApiKeyIsHashed(CreatedKey apiKey) {
        String digest = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM merchant_api_keys WHERE public_id = ?",
                String.class,
                apiKey.publicId()
        );
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(apiKey.rawKey());
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String extractRefreshToken(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        Matcher matcher = REFRESH_COOKIE.matcher(setCookie == null ? "" : setCookie);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static Cookie refreshCookie(String rawRefreshToken) {
        return new Cookie("flowpay_refresh", rawRefreshToken);
    }

    private static String bearer(String credential) {
        return "Bearer " + credential;
    }

    private record Registration(String userPublicId, String normalizedEmail, String merchantPublicId) {
    }

    private record Session(String accessToken, String refreshToken) {
    }

    private record CreatedKey(String publicId, String rawKey) {
    }
}
