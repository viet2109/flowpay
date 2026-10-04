package com.flowpay.backend.webhook.api;

import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.application.ApiKeyManagementUseCase;
import com.flowpay.backend.merchant.application.CreateApiKeyCommand;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import com.flowpay.backend.webhook.application.WebhookSecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookEndpointApiTest extends PostgresIntegrationTest {
    private static final String PATH = "/api/v1/merchant/webhook-endpoints";
    private static final String CREATE = """
            {"url":"https://example.com/hook","events":["refund.succeeded","payment.failed"]}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private MerchantRepository merchants;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private WebhookSecretCipher cipher;
    @Autowired private ApiKeyManagementUseCase apiKeys;
    private Merchant owner;
    private String token;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE TABLE merchants, users RESTART IDENTITY CASCADE");
        owner = merchants.save(Merchant.create("mrc_webhook_owner", "Owner", Instant.now()));
        token = token(owner.publicId());
    }

    @Test
    void createsEncryptedSecretOnceAndReadsOnlyPublicFields() throws Exception {
        JsonNode created = create();
        String id = created.path("id").stringValue();
        String secret = created.path("secret").stringValue();
        String ciphertext = ciphertext(id);
        assertThat(id).startsWith("wep_").hasSize(30);
        assertThat(secret).startsWith("whsec_");
        assertThat(ciphertext).isNotEqualTo(secret).doesNotContain(secret);
        assertThat(cipher.decrypt(ciphertext)).isEqualTo(secret);
        assertThat(created.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "url", "status", "events", "secret", "createdAt", "updatedAt");
        assertThat(created.path("events").toString()).isEqualTo("[\"payment.failed\",\"refund.succeeded\"]");

        MvcResult result = mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn();
        assertSafeRead(data(result), id);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret, ciphertext);
        result = mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1)).andReturn();
        assertSafeRead(data(result).get(0), id);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret, ciphertext);
    }

    @Test
    void patchesUrlAndReplacesWholeEventSetWithoutChangingSecret() throws Exception {
        JsonNode created = create();
        String id = created.path("id").stringValue();
        String before = ciphertext(id);
        MvcResult result = mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"url":" https://other.example/hook ","events":["payment.succeeded"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://other.example/hook"))
                .andExpect(jsonPath("$.data.events.length()").value(1))
                .andExpect(jsonPath("$.data.events[0]").value("payment.succeeded")).andReturn();
        assertSafeRead(data(result), id);
        assertThat(ciphertext(id)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT event_type FROM webhook_endpoint_events", String.class))
                .containsExactly("payment.succeeded");
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_endpoints", Long.class)).isEqualTo(1L);
    }

    @Test
    void patchesIndividualFields() throws Exception {
        String id = create().path("id").stringValue();
        mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://new.example/hook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(2));
        mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\":[\"refund.failed\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://new.example/hook"))
                .andExpect(jsonPath("$.data.events[0]").value("refund.failed"));
    }

    @Test
    void rotatesSecretAndDisablesWithoutDeletionOrReenable() throws Exception {
        JsonNode created = create();
        String id = created.path("id").stringValue();
        String before = ciphertext(id);
        JsonNode rotated = data(mvc.perform(post(PATH + "/" + id + "/rotate-secret")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andReturn());
        assertThat(rotated.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "secret", "updatedAt");
        assertThat(rotated.path("secret").stringValue()).isNotEqualTo(created.path("secret").stringValue());
        assertThat(ciphertext(id)).isNotEqualTo(before);
        assertThat(cipher.decrypt(ciphertext(id))).isEqualTo(rotated.path("secret").stringValue());
        for (int i = 0; i < 2; i++) {
            mvc.perform(delete(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoints", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoint_events", Long.class)).isEqualTo(2);
        mvc.perform(get(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISABLED"))
                .andExpect(jsonPath("$.data.secret").doesNotExist());
        for (MockHttpServletRequestBuilder request : List.of(
                patch(PATH + "/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://new.example\"}"),
                post(PATH + "/" + id + "/rotate-secret"))) {
            mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WEBHOOK_INVALID_STATE"));
        }
        assertThat(cipher.decrypt(ciphertext(id))).isEqualTo(rotated.path("secret").stringValue());
    }

    @Test
    void hidesUnknownAndCrossMerchantEndpointsForEveryOperation() throws Exception {
        String id = create().path("id").stringValue();
        Merchant other = merchants.save(Merchant.create("mrc_webhook_other", "Other", Instant.now()));
        for (String endpointId : List.of(id, "wep_unknown")) {
            for (MockHttpServletRequestBuilder request : List.of(
                    get(PATH + "/" + endpointId),
                    patch(PATH + "/" + endpointId).contentType(MediaType.APPLICATION_JSON).content("{\"events\":[\"payment.failed\"]}"),
                    delete(PATH + "/" + endpointId),
                    post(PATH + "/" + endpointId + "/rotate-secret"))) {
                mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token(other.publicId())))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("WEBHOOK_ENDPOINT_NOT_FOUND"))
                        .andExpect(jsonPath("$.requestId").isString());
            }
        }
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token(other.publicId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_endpoints", String.class)).isEqualTo("ACTIVE");
    }

    @Test
    void requiresDashboardJwtForEveryOperationAndRejectsApiKey() throws Exception {
        String merchantApiKey = apiKeys.create(new CreateApiKeyCommand(
                owner.publicId(), "Webhook auth test")).rawKey();
        for (String authorization : List.of("", "Bearer " + merchantApiKey)) {
            for (MockHttpServletRequestBuilder request : List.of(
                    post(PATH).contentType(MediaType.APPLICATION_JSON).content(CREATE),
                    get(PATH), get(PATH + "/wep_unknown"),
                    patch(PATH + "/wep_unknown").contentType(MediaType.APPLICATION_JSON).content("{}"),
                    delete(PATH + "/wep_unknown"), post(PATH + "/wep_unknown/rotate-secret"))) {
                mvc.perform(request.header(HttpHeaders.AUTHORIZATION, authorization))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            }
        }
    }

    @Test
    void rejectsInactiveAndMissingMerchantsThroughPublicMerchantApi() throws Exception {
        jdbc.update("UPDATE merchants SET status = 'SUSPENDED' WHERE id = ?", owner.id());
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MERCHANT_SUSPENDED"));
        mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, token("mrc_missing")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MERCHANT_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoints", Long.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"url\":\"\"}", "{\"url\":\"   \"}", "{\"events\":[]}",
            "{\"events\":[\"PAYMENT.SUCCEEDED\"]}", "{\"events\":[\"payment.succeeded \"]}",
            "{\"events\":[\"payment.succeeded\",\"payment.succeeded\"]}",
            "{\"events\":[\"payment.unknown\"]}", "{\"events\":[null]}",
            "{\"url\":\"http://example.com/hook\"}", "{\"url\":\"https://127.0.0.1/hook\"}",
            "{\"url\":\"https://169.254.169.254/latest/meta-data\"}",
            "{\"url\":null,\"events\":[\"payment.failed\"]}", "{\"events\":null,\"url\":\"https://new.example\"}"
    })
    void rejectsInvalidPatchAtomically(String body) throws Exception {
        String id = create().path("id").stringValue();
        mvc.perform(patch(PATH + "/" + id).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT version FROM webhook_endpoints", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT url FROM webhook_endpoints", String.class))
                .isEqualTo("https://example.com/hook");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"url\":\"https://example.com\"}",
            "{\"url\":\"https://example.com\",\"events\":[]}",
            "{\"url\":\"https://example.com\",\"events\":[\"payment.succeeded\",\"payment.succeeded\"]}",
            "{\"url\":\"https://example.com\",\"events\":[\"PAYMENT.SUCCEEDED\"]}",
            "{\"url\":\"https://example.com\",\"events\":[null]}",
            "{\"url\":\"http://example.com\",\"events\":[\"payment.failed\"]}"
    })
    void rejectsInvalidCreateWithoutPersisting(String body) throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM webhook_endpoints", Long.class)).isZero();
    }

    private JsonNode create() throws Exception {
        MvcResult result = mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andReturn();
        JsonNode created = data(result);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo(PATH + "/" + created.path("id").stringValue());
        return created;
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String ciphertext(String id) {
        return jdbc.queryForObject("SELECT secret_ciphertext FROM webhook_endpoints WHERE public_id = ?",
                String.class, id);
    }

    private void assertSafeRead(JsonNode node, String id) {
        assertThat(node.path("id").stringValue()).isEqualTo(id);
        assertThat(node.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "url", "status", "events", "createdAt", "updatedAt");
    }

    private String token(String merchantId) {
        Instant now = Instant.now();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
                .issuer("https://flowpay.dev").issuedAt(now).expiresAt(now.plusSeconds(900))
                .subject("usr_webhook_owner").claim("merchant", merchantId).claim("role", "MEMBER")
                .build())).getTokenValue();
    }
}
