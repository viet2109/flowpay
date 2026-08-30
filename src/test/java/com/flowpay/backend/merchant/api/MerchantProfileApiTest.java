package com.flowpay.backend.merchant.api;

import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MerchantProfileApiTest extends PostgresIntegrationTest {

    private static final String USER_PUBLIC_ID = "usr_01KMERCHANTOWNER";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

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
    void shouldRetrieveOwnMerchantWithoutInternalFields() throws Exception {
        Merchant merchant = createMerchant("mrc_01KOWNMERCHANT", "ABC Store");

        mockMvc.perform(get("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(merchant.publicId())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(merchant.publicId()))
                .andExpect(jsonPath("$.data.name").value("ABC Store"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.createdAt").isString())
                .andExpect(jsonPath("$.data.internalId").doesNotExist())
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.membership").doesNotExist())
                .andExpect(jsonPath("$.data.updatedAt").doesNotExist());
    }

    @Test
    void shouldUpdateOwnMerchantEvenWhenClientAttemptsToSelectAnotherMerchant() throws Exception {
        Merchant own = createMerchant("mrc_01KOWNMERCHANT", "ABC Store");
        Merchant other = createMerchant("mrc_01KOTHERMERCHANT", "Other Store");

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(own.publicId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "  ABC Technology Store  ",
                                  "merchantId": "%s"
                                }
                                """.formatted(other.publicId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(own.publicId()))
                .andExpect(jsonPath("$.data.name").value("ABC Technology Store"))
                .andExpect(jsonPath("$.data.version").doesNotExist());

        assertThat(merchantRepository.findByPublicId(own.publicId()).orElseThrow().name())
                .isEqualTo("ABC Technology Store");
        assertThat(merchantRepository.findByPublicId(other.publicId()).orElseThrow().name())
                .isEqualTo("Other Store");
    }

    @Test
    void shouldRejectInvalidMerchantName() throws Exception {
        Merchant merchant = createMerchant("mrc_01KOWNMERCHANT", "ABC Store");
        String token = bearerToken(merchant.publicId());

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        mockMvc.perform(patch("/api/v1/merchant")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"%s\"}".formatted("A".repeat(201))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(merchantRepository.findByPublicId(merchant.publicId()).orElseThrow().name())
                .isEqualTo("ABC Store");
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
}
