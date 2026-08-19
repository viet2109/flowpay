package com.flowpay.backend.identity.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T01:00:00Z");

    @Test
    void shouldNormalizeEmailWhenCreatingUser() {
        User user = newUser(" Viet@Example.COM ");

        assertThat(user.email().value()).isEqualTo("viet@example.com");
        assertThat(user.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.version()).isZero();
    }

    @Test
    void shouldProtectPasswordHashFromStringRepresentations() {
        PasswordHash passwordHash = PasswordHash.of("$2a$10$hashed-password");

        assertThat(passwordHash.toString()).isEqualTo("[REDACTED]");
        assertThat(passwordHash.toString()).doesNotContain(passwordHash.value());
    }

    @Test
    void shouldAllowOnlyValidStatusTransitions() {
        User user = newUser("owner@example.com");
        Instant lockedAt = CREATED_AT.plusSeconds(60);
        Instant disabledAt = lockedAt.plusSeconds(60);

        user.lock(lockedAt);
        assertThat(user.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(user.updatedAt()).isEqualTo(lockedAt);

        user.disable(disabledAt);
        assertThat(user.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(user.updatedAt()).isEqualTo(disabledAt);

        assertThatThrownBy(() -> user.lock(disabledAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only an active user can be locked");
        assertThatThrownBy(() -> user.disable(disabledAt.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("User is already disabled");
    }

    @Test
    void shouldRejectInvalidUserPublicId() {
        assertThatThrownBy(() -> User.create(
                "merchant_01K",
                Email.of("owner@example.com"),
                PasswordHash.of("hash"),
                "Viet",
                "Nguyen",
                CREATED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("publicId must start with usr_ and contain an identifier");
    }

    private static User newUser(String email) {
        return User.create(
                "usr_01K2P1T02TEST",
                Email.of(email),
                PasswordHash.of("$2a$10$hashed-password"),
                " Viet ",
                " Nguyen ",
                CREATED_AT
        );
    }
}
