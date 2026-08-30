package com.flowpay.backend.identity.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.merchant.application.MerchantMembershipApi;
import com.flowpay.backend.merchant.domain.MerchantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordAuthenticationServiceTest {

    private static final String RAW_PASSWORD = "StrongPassword123!";
    private static final PasswordHash STORED_HASH = PasswordHash.of("$2a$10$stored-password-hash");
    private static final Instant CREATED_AT = Instant.parse("2026-08-30T03:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordVerifier passwordVerifier;

    @Mock
    private MerchantMembershipApi merchantMembershipApi;

    private PasswordAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new PasswordAuthenticationService(userRepository, passwordVerifier, merchantMembershipApi);
    }

    @Test
    void shouldAuthenticateValidCredentialsAndResolveMerchantMembership() {
        User user = user();
        given(userRepository.findByEmail(Email.of("viet@example.com"))).willReturn(Optional.of(user));
        given(passwordVerifier.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);
        given(merchantMembershipApi.findForUser(11L)).willReturn(Optional.of(
                new MerchantMembershipApi.Membership("mrc_01KVALID", MerchantRole.OWNER)
        ));

        AuthenticatedIdentity result = service.authenticate(
                new LoginCommand(" Viet@Example.COM ", RAW_PASSWORD)
        );

        assertThat(result).isEqualTo(new AuthenticatedIdentity(
                "usr_01KVALID",
                "viet@example.com",
                "mrc_01KVALID",
                MerchantRole.OWNER
        ));
        verify(userRepository).findByEmail(Email.of("viet@example.com"));
        verify(merchantMembershipApi).findForUser(11L);
    }

    @Test
    void shouldReturnInvalidCredentialsForWrongPassword() {
        given(userRepository.findByEmail(Email.of("viet@example.com"))).willReturn(Optional.of(user()));
        given(passwordVerifier.matches("wrong-password", STORED_HASH)).willReturn(false);

        assertInvalidCredentials(new LoginCommand("viet@example.com", "wrong-password"));

        verify(merchantMembershipApi, never()).findForUser(anyLong());
    }

    @Test
    void shouldReturnSameInvalidCredentialsForUnknownEmailAndPerformDummyVerification() {
        given(userRepository.findByEmail(Email.of("missing@example.com"))).willReturn(Optional.empty());
        given(passwordVerifier.matches(eq(RAW_PASSWORD), any(PasswordHash.class))).willReturn(false);

        assertInvalidCredentials(new LoginCommand("missing@example.com", RAW_PASSWORD));

        verify(passwordVerifier).matches(eq(RAW_PASSWORD), any(PasswordHash.class));
        verify(merchantMembershipApi, never()).findForUser(anyLong());
    }

    @Test
    void shouldRejectLockedUserAfterPasswordVerification() {
        User user = user();
        user.lock(CREATED_AT.plusSeconds(1));
        given(userRepository.findByEmail(user.email())).willReturn(Optional.of(user));
        given(passwordVerifier.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);

        assertStatusError(new LoginCommand(user.email().value(), RAW_PASSWORD), ErrorCode.USER_LOCKED);

        verify(merchantMembershipApi, never()).findForUser(anyLong());
    }

    @Test
    void shouldRejectDisabledUserAfterPasswordVerification() {
        User user = user();
        user.disable(CREATED_AT.plusSeconds(1));
        given(userRepository.findByEmail(user.email())).willReturn(Optional.of(user));
        given(passwordVerifier.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);

        assertStatusError(new LoginCommand(user.email().value(), RAW_PASSWORD), ErrorCode.USER_DISABLED);

        verify(merchantMembershipApi, never()).findForUser(anyLong());
    }

    @Test
    void shouldNotExposePasswordInCommandOrAuthenticationErrors() {
        LoginCommand command = new LoginCommand("missing@example.com", RAW_PASSWORD);
        given(userRepository.findByEmail(Email.of(command.email()))).willReturn(Optional.empty());

        assertThat(command.toString()).doesNotContain(RAW_PASSWORD).contains("[REDACTED]");
        assertThatThrownBy(() -> service.authenticate(command))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getMessage()).doesNotContain(RAW_PASSWORD));
    }

    @Test
    void shouldReportMissingMerchantWithoutExposingInternalUserId() {
        User user = user();
        given(userRepository.findByEmail(user.email())).willReturn(Optional.of(user));
        given(passwordVerifier.matches(RAW_PASSWORD, STORED_HASH)).willReturn(true);
        given(merchantMembershipApi.findForUser(11L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(new LoginCommand(user.email().value(), RAW_PASSWORD)))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.MERCHANT_NOT_FOUND);
                    assertThat(exception.getMessage()).doesNotContain("11").doesNotContain(RAW_PASSWORD);
                });
    }

    private void assertInvalidCredentials(LoginCommand command) {
        assertThatThrownBy(() -> service.authenticate(command))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(401);
                    assertThat(exception.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                    assertThat(exception.getMessage()).isEqualTo("The email or password is incorrect.");
                    assertThat(exception.getMessage()).doesNotContain(command.email()).doesNotContain(command.password());
                });
    }

    private void assertStatusError(LoginCommand command, ErrorCode expectedCode) {
        assertThatThrownBy(() -> service.authenticate(command))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(401);
                    assertThat(exception.code()).isEqualTo(expectedCode);
                    assertThat(exception.getMessage()).doesNotContain(command.password());
                });
    }

    private static User user() {
        return User.rehydrate(
                11L,
                "usr_01KVALID",
                Email.of("viet@example.com"),
                STORED_HASH,
                "Viet",
                "Nguyen",
                com.flowpay.backend.identity.domain.UserStatus.ACTIVE,
                0,
                CREATED_AT,
                CREATED_AT
        );
    }
}
