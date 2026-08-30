package com.flowpay.backend.identity.application;

import java.util.Objects;

public record RefreshResult(IssuedAccessToken accessToken, IssuedRefreshToken refreshToken) {

    public RefreshResult {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(refreshToken, "refreshToken must not be null");
    }

    @Override
    public String toString() {
        return "RefreshResult[accessToken=[REDACTED], refreshToken=[REDACTED]]";
    }
}
