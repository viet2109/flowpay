package com.flowpay.backend.identity.api;

public record RefreshResponse(String accessToken, long expiresIn) {

    @Override
    public String toString() {
        return "RefreshResponse[accessToken=[REDACTED], expiresIn=" + expiresIn + "]";
    }
}
