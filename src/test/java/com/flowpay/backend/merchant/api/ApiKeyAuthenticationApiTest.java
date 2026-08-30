package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.GeneratedApiKeySecret;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.Merchant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationApiTest {

    private static final String PAYMENT_PATH = "/api/v1/payment-intents";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ApiKeySecretCodec secretCodec;

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
    void shouldAuthenticateValidKeyWithoutCreatingAFakePaymentEndpoint() throws Exception {
        StoredKey stored = createStoredKey();

        mockMvc.perform(get(PAYMENT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertThat(apiKeyRepository.findByPublicId(stored.apiKeyPublicId()).orElseThrow().lastUsedAt())
                .isNotNull();
    }

    @Test
    void shouldRejectMalformedUnknownAndModifiedKeysWithoutExposingSecrets(CapturedOutput output) throws Exception {
        StoredKey stored = createStoredKey();
        GeneratedApiKeySecret unknown = secretCodec.generate();
        String modified = stored.rawKey().substring(0, stored.rawKey().length() - 1)
                + differentCharacter(stored.rawKey().charAt(stored.rawKey().length() - 1));

        assertInvalidKey("fp_test_short");
        assertInvalidKey(unknown.rawKey());
        assertInvalidKey(modified);

        assertThat(output.getAll())
                .doesNotContain(stored.rawKey())
                .doesNotContain(unknown.rawKey())
                .doesNotContain(modified);
    }

    @Test
    void shouldRejectRevokedKey() throws Exception {
        StoredKey stored = createStoredKey();
        ApiKey apiKey = apiKeyRepository.findByPublicId(stored.apiKeyPublicId()).orElseThrow();
        apiKey.revoke(Instant.now());
        apiKeyRepository.save(apiKey);

        mockMvc.perform(get(PAYMENT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("API_KEY_REVOKED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(stored.rawKey())
                )));
    }

    @Test
    void shouldRejectSuspendedMerchant() throws Exception {
        StoredKey stored = createStoredKey();
        jdbcTemplate.update(
                "UPDATE merchants SET status = 'SUSPENDED' WHERE public_id = ?",
                stored.merchantPublicId()
        );

        mockMvc.perform(get(PAYMENT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(stored.rawKey())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(stored.rawKey())
                )));
    }

    @Test
    void shouldRequireApiKeyOnMerchantIntegrationPaths() throws Exception {
        mockMvc.perform(get(PAYMENT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private void assertInvalidKey(String rawKey) throws Exception {
        mockMvc.perform(get(PAYMENT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(rawKey)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(rawKey)
                )));
    }

    private StoredKey createStoredKey() {
        Merchant merchant = merchantRepository.save(Merchant.create(
                "mrc_api_auth_owner",
                "API Auth Store",
                Instant.now()
        ));
        GeneratedApiKeySecret secret = secretCodec.generate();
        ApiKey apiKey = apiKeyRepository.save(ApiKey.create(
                "key_api_auth",
                merchant.id(),
                "Backend",
                secret.prefix(),
                secret.digest(),
                null,
                Instant.now()
        ));
        return new StoredKey(merchant.publicId(), apiKey.publicId(), secret.rawKey());
    }

    private static String bearer(String rawKey) {
        return "Bearer " + rawKey;
    }

    private static char differentCharacter(char current) {
        return current == 'A' ? 'B' : 'A';
    }

    private record StoredKey(String merchantPublicId, String apiKeyPublicId, String rawKey) {
    }
}
