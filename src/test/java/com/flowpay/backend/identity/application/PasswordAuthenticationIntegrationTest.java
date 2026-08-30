package com.flowpay.backend.identity.application;

import com.flowpay.backend.merchant.domain.MerchantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PasswordAuthenticationIntegrationTest {

    private static final String RAW_PASSWORD = "StrongPassword123!";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private RegistrationUseCase registrationUseCase;

    @Autowired
    private PasswordAuthenticationUseCase authenticationUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanIdentityData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE refresh_tokens, merchant_api_keys, merchant_members,
                    merchants, users RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldAuthenticatePersistedBcryptCredentialsAndResolveOwnerMembership() {
        RegistrationResult registration = registrationUseCase.register(new RegistrationCommand(
                " Viet@Example.COM ",
                RAW_PASSWORD,
                "Viet",
                "Nguyen",
                "ABC Store"
        ));

        AuthenticatedIdentity authenticated = authenticationUseCase.authenticate(
                new LoginCommand("VIET@example.com", RAW_PASSWORD)
        );

        assertThat(authenticated.userPublicId()).isEqualTo(registration.user().publicId());
        assertThat(authenticated.email()).isEqualTo("viet@example.com");
        assertThat(authenticated.merchantPublicId()).isEqualTo(registration.merchant().publicId());
        assertThat(authenticated.role()).isEqualTo(MerchantRole.OWNER);
    }
}
