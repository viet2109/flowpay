package com.flowpay.backend.identity.application;

import java.util.Objects;

public record LoginResult(
        IssuedAccessToken accessToken,
        IssuedRefreshToken refreshToken,
        UserSnapshot user
) {

    public LoginResult {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
        Objects.requireNonNull(user, "user must not be null");
    }

    @Override
    public String toString() {
        return "LoginResult[accessToken=[REDACTED], refreshToken=[REDACTED], user=" + user + "]";
    }

    public record UserSnapshot(String publicId, String email) {

        public UserSnapshot {
            Objects.requireNonNull(publicId, "publicId must not be null");
            Objects.requireNonNull(email, "email must not be null");
        }
    }
}
