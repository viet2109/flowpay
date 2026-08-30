package com.flowpay.backend.identity.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.identity.domain.UserStatus;
import com.flowpay.backend.merchant.application.MerchantMembershipApi;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PasswordAuthenticationService implements PasswordAuthenticationUseCase {

    private static final String INVALID_CREDENTIALS_DETAIL = "The email or password is incorrect.";
    private static final PasswordHash DUMMY_PASSWORD_HASH = PasswordHash.of(
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5QZQ8TzA6hYQZqPzJZ7GvH8J2Q5bB4K"
    );

    private final UserRepository userRepository;
    private final PasswordVerifier passwordVerifier;
    private final MerchantMembershipApi merchantMembershipApi;

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedIdentity authenticate(LoginCommand command) {
        Email email = Email.of(command.email());
        User user = userRepository.findByEmail(email).orElse(null);

        PasswordHash passwordHash = user == null ? DUMMY_PASSWORD_HASH : user.passwordHash();
        boolean passwordMatches = passwordVerifier.matches(command.password(), passwordHash);
        if (user == null || !passwordMatches) {
            throw invalidCredentials();
        }

        requireLoginAllowed(user.status());

        MerchantMembershipApi.Membership membership = merchantMembershipApi.findForUser(user.id())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        ErrorCode.MERCHANT_NOT_FOUND,
                        "No merchant membership is available for this user."
                ));

        return new AuthenticatedIdentity(
                user.publicId(),
                user.email().value(),
                membership.merchantPublicId(),
                membership.role()
        );
    }

    private static void requireLoginAllowed(UserStatus status) {
        if (status == UserStatus.LOCKED) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.USER_LOCKED, "This user account is locked.");
        }
        if (status == UserStatus.DISABLED) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.USER_DISABLED, "This user account is disabled.");
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
    }
}
