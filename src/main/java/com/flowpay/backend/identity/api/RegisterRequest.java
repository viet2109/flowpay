package com.flowpay.backend.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be valid.")
        @Size(max = 320, message = "Email must not exceed 320 characters.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, message = "Password must contain at least 8 characters.")
        String password,

        @NotBlank(message = "First name is required.")
        @Size(max = 100, message = "First name must not exceed 100 characters.")
        String firstName,

        @NotBlank(message = "Last name is required.")
        @Size(max = 100, message = "Last name must not exceed 100 characters.")
        String lastName,

        @NotBlank(message = "Merchant name is required.")
        @Size(max = 200, message = "Merchant name must not exceed 200 characters.")
        String merchantName
) {

    public RegisterRequest {
        if (email != null) {
            email = email.trim();
        }
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email
                + ", password=[REDACTED], firstName=" + firstName
                + ", lastName=" + lastName
                + ", merchantName=" + merchantName + "]";
    }
}
