package com.flowpay.backend.identity.api;

public record LoginResponse(String accessToken, long expiresIn, UserView user) {

    @Override
    public String toString() {
        return "LoginResponse[accessToken=[REDACTED], expiresIn=" + expiresIn + ", user=" + user + "]";
    }

    public record UserView(String id, String email) {
    }
}
