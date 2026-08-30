package com.flowpay.backend.identity.application;

import com.flowpay.backend.identity.domain.PasswordHash;

public interface PasswordVerifier {

    boolean matches(String rawPassword, PasswordHash passwordHash);
}
