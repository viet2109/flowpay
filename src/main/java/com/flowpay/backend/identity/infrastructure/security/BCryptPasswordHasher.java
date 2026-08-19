package com.flowpay.backend.identity.infrastructure.security;

import com.flowpay.backend.identity.application.PasswordHasher;
import com.flowpay.backend.identity.domain.PasswordHash;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
final class BCryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder passwordEncoder;

    BCryptPasswordHasher(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public PasswordHash hash(String rawPassword) {
        return PasswordHash.of(passwordEncoder.encode(rawPassword));
    }
}
