package com.flowpay.backend.identity.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.identity.domain.RefreshToken;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.identity.domain.UserStatus;
import com.flowpay.backend.merchant.application.MerchantMembershipApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class AuthSessionService implements AuthSessionUseCase {

    private final PasswordAuthenticationUseCase passwordAuthentication;
    private final UserRepository userRepository;
    private final MerchantMembershipApi merchantMembershipApi;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenCodec refreshTokenCodec;
    private final AccessTokenIssuer accessTokenIssuer;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    public AuthSessionService(
            PasswordAuthenticationUseCase passwordAuthentication,
            UserRepository userRepository,
            MerchantMembershipApi merchantMembershipApi,
            RefreshTokenRepository refreshTokenRepository,
            RefreshTokenCodec refreshTokenCodec,
            AccessTokenIssuer accessTokenIssuer,
            @Qualifier("refreshTokenTtl") Duration refreshTokenTtl,
            Clock clock
    ) {
        this.passwordAuthentication = passwordAuthentication;
        this.userRepository = userRepository;
        this.merchantMembershipApi = merchantMembershipApi;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenCodec = refreshTokenCodec;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenTtl = refreshTokenTtl;
        this.clock = clock;
    }

    @Override
    @Transactional
    public LoginResult login(LoginCommand command) {
        AuthenticatedIdentity identity = passwordAuthentication.authenticate(command);
        User user = userRepository.findByPublicId(identity.userPublicId())
                .orElseThrow(AuthSessionService::invalidCredentials);

        CreatedRefreshToken refreshToken = issueRefreshToken(user.id(), clock.instant());
        IssuedAccessToken accessToken = accessTokenIssuer.issue(identity);
        return new LoginResult(
                accessToken,
                refreshToken.secret(),
                new LoginResult.UserSnapshot(identity.userPublicId(), identity.email())
        );
    }

    @Override
    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        requireValidFormat(rawRefreshToken);
        Instant now = clock.instant();
        RefreshToken current = refreshTokenRepository
                .findByTokenHashForUpdate(refreshTokenCodec.digest(rawRefreshToken))
                .orElseThrow(AuthSessionService::invalidRefreshToken);

        if (current.isRevoked()) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.REFRESH_TOKEN_REVOKED,
                    "The refresh token has been revoked."
            );
        }
        if (current.isExpired(now)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.REFRESH_TOKEN_EXPIRED,
                    "The refresh token has expired."
            );
        }

        User user = userRepository.findById(current.userId()).orElseThrow(AuthSessionService::invalidRefreshToken);
        AuthenticatedIdentity identity = resolveIdentity(user);
        CreatedRefreshToken replacement = issueRefreshToken(user.id(), now);

        current.rotateTo(replacement.token().id(), now);
        refreshTokenRepository.save(current);

        return new RefreshResult(accessTokenIssuer.issue(identity), replacement.secret());
    }

    @Override
    @Transactional
    public void logout(String rawRefreshToken) {
        if (!refreshTokenCodec.hasValidFormat(rawRefreshToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHashForUpdate(refreshTokenCodec.digest(rawRefreshToken))
                .filter(token -> !token.isRevoked())
                .ifPresent(token -> {
                    token.revoke(clock.instant());
                    refreshTokenRepository.save(token);
                });
    }

    private CreatedRefreshToken issueRefreshToken(long userId, Instant now) {
        String rawToken = refreshTokenCodec.generate();
        Instant expiresAt = now.plus(refreshTokenTtl);
        RefreshToken saved = refreshTokenRepository.save(RefreshToken.create(
                userId,
                refreshTokenCodec.digest(rawToken),
                expiresAt,
                now
        ));
        return new CreatedRefreshToken(saved, new IssuedRefreshToken(rawToken, expiresAt));
    }

    private AuthenticatedIdentity resolveIdentity(User user) {
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

    private void requireValidFormat(String rawRefreshToken) {
        if (!refreshTokenCodec.hasValidFormat(rawRefreshToken)) {
            throw invalidRefreshToken();
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_CREDENTIALS,
                "The email or password is incorrect."
        );
    }

    private static ApiException invalidRefreshToken() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.REFRESH_TOKEN_INVALID,
                "The refresh token is invalid."
        );
    }

    private record CreatedRefreshToken(RefreshToken token, IssuedRefreshToken secret) {
    }
}
