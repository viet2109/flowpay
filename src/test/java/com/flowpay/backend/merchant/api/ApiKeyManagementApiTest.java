package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.application.ApiKeyRepository;
import com.flowpay.backend.merchant.application.ApiKeySecretCodec;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.ApiKey;
import com.flowpay.backend.merchant.domain.ApiKeyStatus;
import com.flowpay.backend.merchant.domain.Merchant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ApiKeyManagementApiTest {

    private static final String USER_PUBLIC_ID = "usr_01KAPIKEYOWNER";

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
    private JwtEncoder jwtEncoder;

    @Autowired
    private ObjectMapper objectMapper;

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
    void shouldCreateKeyOnceAndListWithoutSecretOrHash() throws Exception {
        Merchant merchant = createMerchant("mrc_01KAPIKEYOWNER", "API Key Store");

        CreatedResponse created = createApiKey(merchant.publicId(), " Development backend ");
        ApiKey stored = apiKeyRepository.findByPublicIdAndMerchantId(created.id(), merchant.id()).orElseThrow();
        String persistedHash = jdbcTemplate.queryForObject(
                "SELECT key_hash FROM merchant_api_keys WHERE public_id = ?",
                String.class,
                created.id()
        );

        assertThat(created.id()).startsWith("key_").hasSize(30);
        assertThat(secretCodec.hasValidFormat(created.rawKey())).isTrue();
        assertThat(created.rawKey()).startsWith(created.prefix());
        assertThat(stored.name()).isEqualTo("Development backend");
        assertThat(stored.keyHash()).isEqualTo(secretCodec.digest(created.rawKey()));
        assertThat(persistedHash).isEqualTo(stored.keyHash()).isNotEqualTo(created.rawKey());

        mockMvc.perform(get("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(merchant.publicId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(created.id()))
                .andExpect(jsonPath("$.data[0].name").value("Development backend"))
                .andExpect(jsonPath("$.data[0].prefix").value(created.prefix()))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].createdAt").isString())
                .andExpect(jsonPath("$.data[0].key").doesNotExist())
                .andExpect(jsonPath("$.data[0].rawKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].keyHash").doesNotExist())
                .andExpect(jsonPath("$.data[0].merchantId").doesNotExist())
                .andExpect(jsonPath("$.data[0].internalId").doesNotExist());
    }

    @Test
    void shouldRevokeWithoutDeletingAndHideCrossMerchantKeysAsNotFound() throws Exception {
        Merchant owner = createMerchant("mrc_01KAPIKEYOWNER", "Owner Store");
        Merchant other = createMerchant("mrc_01KAPIKEYOTHER", "Other Store");
        CreatedResponse otherKey = createApiKey(other.publicId(), "Other backend");

        mockMvc.perform(delete("/api/v1/merchant/api-keys/{keyId}", otherKey.id())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(owner.publicId())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));

        assertThat(apiKeyRepository.findByPublicIdAndMerchantId(otherKey.id(), other.id()).orElseThrow().status())
                .isEqualTo(ApiKeyStatus.ACTIVE);

        String otherToken = bearerToken(other.publicId());
        mockMvc.perform(delete("/api/v1/merchant/api-keys/{keyId}", otherKey.id())
                        .header(HttpHeaders.AUTHORIZATION, otherToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(delete("/api/v1/merchant/api-keys/{keyId}", otherKey.id())
                        .header(HttpHeaders.AUTHORIZATION, otherToken))
                .andExpect(status().isNoContent());

        ApiKey revoked = apiKeyRepository.findByPublicIdAndMerchantId(otherKey.id(), other.id()).orElseThrow();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM merchant_api_keys WHERE public_id = ?",
                Integer.class,
                otherKey.id()
        );
        assertThat(revoked.status()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void shouldRequireDashboardJwt() throws Exception {
        mockMvc.perform(get("/api/v1/merchant/api-keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void shouldRejectInvalidDisplayName() throws Exception {
        Merchant merchant = createMerchant("mrc_01KAPIKEYOWNER", "API Key Store");
        String token = bearerToken(merchant.publicId());

        mockMvc.perform(post("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mockMvc.perform(post("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted("A".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(apiKeyRepository.findAllByMerchantId(merchant.id())).isEmpty();
    }

    private CreatedResponse createApiKey(String merchantPublicId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/merchant/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(merchantPublicId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.key").isString())
                .andExpect(jsonPath("$.data.prefix").isString())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.keyHash").doesNotExist())
                .andExpect(jsonPath("$.data.internalId").doesNotExist())
                .andReturn();
        var data = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        return new CreatedResponse(
                data.path("id").stringValue(),
                data.path("key").stringValue(),
                data.path("prefix").stringValue()
        );
    }

    private Merchant createMerchant(String publicId, String name) {
        return merchantRepository.save(Merchant.create(publicId, name, Instant.now()));
    }

    private String bearerToken(String merchantPublicId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("https://flowpay.dev")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .subject(USER_PUBLIC_ID)
                .claim("merchant", merchantPublicId)
                .claim("role", "OWNER")
                .build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private record CreatedResponse(String id, String rawKey, String prefix) {
    }
}
