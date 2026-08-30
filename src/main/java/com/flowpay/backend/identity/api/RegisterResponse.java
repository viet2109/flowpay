package com.flowpay.backend.identity.api;

public record RegisterResponse(UserView user, MerchantView merchant) {

    public record UserView(String id, String email, String firstName, String lastName) {
    }

    public record MerchantView(String id, String name, String status) {
    }
}
