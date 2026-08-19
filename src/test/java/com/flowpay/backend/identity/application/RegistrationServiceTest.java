package com.flowpay.backend.identity.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.identity.domain.UserStatus;
import com.flowpay.backend.merchant.application.MerchantOnboardingApi;
import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordHasher passwordHasher;

    @Mock
    private UserPublicIdGenerator publicIdGenerator;

    @Mock
    private MerchantOnboardingApi onboardingApi;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                userRepository,
                passwordHasher,
                publicIdGenerator,
                onboardingApi
        );
    }

    @Test
    void shouldOrchestrateRegistrationWithNormalizedEmailAndHashedPassword() {
        RegistrationCommand command = command(" Viet@Example.COM ");
        PasswordHash hash = PasswordHash.of("$2a$10$hashed-password");
        given(userRepository.findByEmail(Email.of("viet@example.com"))).willReturn(Optional.empty());
        given(passwordHasher.hash(command.password())).willReturn(hash);
        given(publicIdGenerator.nextId()).willReturn("usr_01K2P1T04TEST");
        given(userRepository.save(any(User.class))).willAnswer(invocation -> persisted(invocation.getArgument(0)));
        given(onboardingApi.onboard(any())).willReturn(new MerchantOnboardingApi.Result(
                "mrc_01K2P1T04TEST",
                "ABC Store",
                MerchantStatus.ACTIVE,
                MerchantRole.OWNER
        ));

        RegistrationResult result = service.register(command);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User user = userCaptor.getValue();
        assertThat(user.email()).isEqualTo(Email.of("viet@example.com"));
        assertThat(user.passwordHash()).isEqualTo(hash);
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);

        ArgumentCaptor<MerchantOnboardingApi.Command> onboardingCaptor =
                ArgumentCaptor.forClass(MerchantOnboardingApi.Command.class);
        verify(onboardingApi).onboard(onboardingCaptor.capture());
        assertThat(onboardingCaptor.getValue().userId()).isEqualTo(1L);
        assertThat(onboardingCaptor.getValue().merchantName()).isEqualTo("ABC Store");
        assertThat(result.user().email()).isEqualTo("viet@example.com");
        assertThat(result.merchant().status()).isEqualTo("ACTIVE");
    }

    @Test
    void shouldRejectExistingEmailBeforeHashingPassword() {
        RegistrationCommand command = command("owner@example.com");
        given(userRepository.findByEmail(Email.of(command.email())))
                .willReturn(Optional.of(existingUser()));

        assertThatThrownBy(() -> service.register(command))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(409);
                    assertThat(exception.code()).isEqualTo(ErrorCode.USER_EMAIL_ALREADY_EXISTS);
                });

        verify(passwordHasher, never()).hash(any());
        verify(onboardingApi, never()).onboard(any());
    }

    @Test
    void shouldTranslateDatabaseEmailRaceToStableConflict() {
        RegistrationCommand command = command("race@example.com");
        given(userRepository.findByEmail(Email.of(command.email()))).willReturn(Optional.empty());
        given(passwordHasher.hash(command.password())).willReturn(PasswordHash.of("hash"));
        given(publicIdGenerator.nextId()).willReturn("usr_race");
        given(userRepository.save(any(User.class))).willThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.register(command))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.USER_EMAIL_ALREADY_EXISTS));

        verify(onboardingApi, never()).onboard(any());
    }

    private static RegistrationCommand command(String email) {
        return new RegistrationCommand(
                email,
                "StrongPassword123!",
                "Viet",
                "Nguyen",
                "ABC Store"
        );
    }

    private static User persisted(User user) {
        return User.rehydrate(
                1L,
                user.publicId(),
                user.email(),
                user.passwordHash(),
                user.firstName(),
                user.lastName(),
                user.status(),
                0,
                user.createdAt(),
                user.updatedAt()
        );
    }

    private static User existingUser() {
        Instant now = Instant.parse("2026-08-19T05:00:00Z");
        return User.rehydrate(
                1L,
                "usr_existing",
                Email.of("owner@example.com"),
                PasswordHash.of("hash"),
                "Viet",
                "Nguyen",
                UserStatus.ACTIVE,
                0,
                now,
                now
        );
    }
}
