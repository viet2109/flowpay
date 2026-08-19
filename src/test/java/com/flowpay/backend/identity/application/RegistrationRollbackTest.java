package com.flowpay.backend.identity.application;

import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.merchant.application.MerchantOnboardingApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RegistrationRollbackTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @MockitoBean
    private MerchantOnboardingApi onboardingApi;

    @Autowired
    private RegistrationUseCase registrationUseCase;

    @Autowired
    private UserRepository userRepository;

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
    void shouldRollbackUserWhenMerchantOnboardingFails() {
        given(onboardingApi.onboard(any())).willThrow(new IllegalStateException("onboarding failed"));
        RegistrationCommand command = new RegistrationCommand(
                "rollback@example.com",
                "StrongPassword123!",
                "Viet",
                "Nguyen",
                "ABC Store"
        );

        assertThatThrownBy(() -> registrationUseCase.register(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("onboarding failed");

        assertThat(userRepository.findByEmail(Email.of(command.email()))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchants", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM merchant_members", Integer.class)).isZero();
    }
}
