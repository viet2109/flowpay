package com.flowpay.backend.identity.infrastructure.security;

import com.flowpay.backend.identity.domain.PasswordHash;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordHasherTest {

    private final BCryptPasswordHasher passwords = new BCryptPasswordHasher(new BCryptPasswordEncoder(4));

    @Test
    void shouldHashAndVerifyPasswordWithBCrypt() {
        PasswordHash hash = passwords.hash("StrongPassword123!");

        assertThat(hash.value()).startsWith("$2");
        assertThat(passwords.matches("StrongPassword123!", hash)).isTrue();
        assertThat(passwords.matches("wrong-password", hash)).isFalse();
    }
}
