package com.flowpay.backend.identity.infrastructure.persistence;

import com.flowpay.backend.identity.application.UserRepository;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;
import com.flowpay.backend.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UserPersistenceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T02:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.update("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
    }

    @Test
    void shouldPersistAndReloadUserWithoutExposingJpaEntity() {
        User saved = userRepository.save(newUser("usr_persist", " Persist@Example.COM "));

        User reloaded = userRepository.findByPublicId(saved.publicId()).orElseThrow();

        assertThat(saved.id()).isPositive();
        assertThat(reloaded.id()).isEqualTo(saved.id());
        assertThat(reloaded.publicId()).isEqualTo("usr_persist");
        assertThat(reloaded.email()).isEqualTo(Email.of("persist@example.com"));
        assertThat(reloaded.passwordHash()).isEqualTo(PasswordHash.of("$2a$10$persisted-hash"));
        assertThat(reloaded.firstName()).isEqualTo("Viet");
        assertThat(reloaded.lastName()).isEqualTo("Nguyen");
        assertThat(reloaded.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reloaded.version()).isZero();
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldFindUserByNormalizedEmail() {
        User saved = userRepository.save(newUser("usr_email_lookup", " Owner@Example.COM "));

        User found = userRepository.findByEmail(Email.of("OWNER@example.com")).orElseThrow();

        assertThat(found.publicId()).isEqualTo(saved.publicId());
        assertThat(found.email()).isEqualTo(Email.of("owner@example.com"));
    }

    @Test
    void shouldRejectDuplicateNormalizedEmail() {
        userRepository.save(newUser("usr_duplicate_1", "Owner@Example.com"));

        assertThatThrownBy(() -> userRepository.save(
                newUser("usr_duplicate_2", " owner@example.COM ")
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldIncrementVersionAndRejectStaleUpdates() {
        User saved = userRepository.save(newUser("usr_version", "version@example.com"));
        User firstCopy = userRepository.findByPublicId(saved.publicId()).orElseThrow();
        User staleCopy = userRepository.findByPublicId(saved.publicId()).orElseThrow();

        firstCopy.lock(CREATED_AT.plusSeconds(60));
        User updated = userRepository.save(firstCopy);

        assertThat(updated.status()).isEqualTo(UserStatus.LOCKED);
        assertThat(updated.version()).isEqualTo(1);

        staleCopy.disable(CREATED_AT.plusSeconds(120));
        assertThatThrownBy(() -> userRepository.save(staleCopy))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void shouldPersistStatusBehavior() {
        User saved = userRepository.save(newUser("usr_status", "status@example.com"));
        saved.disable(CREATED_AT.plusSeconds(60));

        User disabled = userRepository.save(saved);
        User reloaded = userRepository.findByPublicId(saved.publicId()).orElseThrow();

        assertThat(disabled.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(reloaded.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(reloaded.updatedAt()).isEqualTo(CREATED_AT.plusSeconds(60));
    }

    private static User newUser(String publicId, String email) {
        return User.create(
                publicId,
                Email.of(email),
                PasswordHash.of("$2a$10$persisted-hash"),
                "Viet",
                "Nguyen",
                CREATED_AT
        );
    }
}
