package com.flowpay.backend.identity.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RegistrationApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanRegistrationData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldRegisterUserMerchantAndOwnerMembership() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Request-Id", "req_registration_success")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(" Viet@Example.COM ")))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", "req_registration_success"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.user.id", startsWith("usr_")))
                .andExpect(jsonPath("$.data.user.email").value("viet@example.com"))
                .andExpect(jsonPath("$.data.user.firstName").value("Viet"))
                .andExpect(jsonPath("$.data.user.lastName").value("Nguyen"))
                .andExpect(jsonPath("$.data.merchant.id", startsWith("mrc_")))
                .andExpect(jsonPath("$.data.merchant.name").value("ABC Store"))
                .andExpect(jsonPath("$.data.merchant.status").value("ACTIVE"));

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("merchants")).isEqualTo(1);
        assertThat(count("merchant_members")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM merchant_members",
                String.class
        )).isEqualTo("OWNER");

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users",
                String.class
        );
        assertThat(passwordHash)
                .startsWith("$2")
                .doesNotContain("StrongPassword123!");
    }

    @Test
    void shouldReturnConflictForDuplicateNormalizedEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("Owner@Example.COM")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Request-Id", "req_duplicate_email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("owner@example.com")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USER_EMAIL_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.requestId").value("req_duplicate_email"))
                .andExpect(jsonPath("$.detail").value("A user with this email already exists."));

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("merchants")).isEqualTo(1);
        assertThat(count("merchant_members")).isEqualTo(1);
    }

    @Test
    void shouldRejectInvalidEmail() throws Exception {
        assertValidationError(validRequest("not-an-email"), "email");
    }

    @Test
    void shouldRejectPasswordShorterThanEightCharacters() throws Exception {
        assertValidationError("""
                {
                  "email": "viet@example.com",
                  "password": "short",
                  "firstName": "Viet",
                  "lastName": "Nguyen",
                  "merchantName": "ABC Store"
                }
                """, "password");
    }

    @Test
    void shouldRejectMissingMerchantName() throws Exception {
        assertValidationError("""
                {
                  "email": "viet@example.com",
                  "password": "StrongPassword123!",
                  "firstName": "Viet",
                  "lastName": "Nguyen"
                }
                """, "merchantName");
    }

    private void assertValidationError(String requestBody, String field) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Request-Id", "req_validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req_validation"))
                .andExpect(jsonPath("$.errors[?(@.field == '" + field + "')]").exists());
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static String validRequest(String email) {
        return """
                {
                  "email": "%s",
                  "password": "StrongPassword123!",
                  "firstName": "Viet",
                  "lastName": "Nguyen",
                  "merchantName": " ABC Store "
                }
                """.formatted(email);
    }
}
