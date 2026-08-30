package com.flowpay.backend.identity.application;

import java.util.Objects;

public record RegistrationCommand(
        String email,
        String password,
        String firstName,
        String lastName,
        String merchantName
) {

    public RegistrationCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(firstName, "firstName must not be null");
        Objects.requireNonNull(lastName, "lastName must not be null");
        Objects.requireNonNull(merchantName, "merchantName must not be null");
        if (password.length() < 8) {
            throw new IllegalArgumentException("password must contain at least 8 characters");
        }
        if (merchantName.isBlank()) {
            throw new IllegalArgumentException("merchantName must not be blank");
        }
    }

    @Override
    public String toString() {
        return "RegistrationCommand[email=" + email
                + ", password=[REDACTED], firstName=" + firstName
                + ", lastName=" + lastName
                + ", merchantName=" + merchantName + "]";
    }
}
