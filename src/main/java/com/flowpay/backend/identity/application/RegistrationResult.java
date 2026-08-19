package com.flowpay.backend.identity.application;

public record RegistrationResult(UserSnapshot user, MerchantSnapshot merchant) {

    public record UserSnapshot(String publicId, String email, String firstName, String lastName) {
    }

    public record MerchantSnapshot(String publicId, String name, String status) {
    }
}
