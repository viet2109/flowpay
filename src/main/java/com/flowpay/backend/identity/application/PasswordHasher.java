package com.flowpay.backend.identity.application;

import com.flowpay.backend.identity.domain.PasswordHash;

public interface PasswordHasher {

    PasswordHash hash(String rawPassword);
}
