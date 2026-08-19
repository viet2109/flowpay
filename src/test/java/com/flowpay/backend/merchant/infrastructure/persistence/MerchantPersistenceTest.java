package com.flowpay.backend.merchant.infrastructure.persistence;

import com.flowpay.backend.merchant.application.MerchantMemberRepository;
import com.flowpay.backend.merchant.application.MerchantOnboardingApi;
import com.flowpay.backend.merchant.application.MerchantRepository;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantMember;
import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MerchantPersistenceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T04:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Autowired
    private MerchantOnboardingApi onboardingApi;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private MerchantMemberRepository memberRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanMerchantData() {
        jdbcTemplate.update("TRUNCATE TABLE merchant_members, merchants, users RESTART IDENTITY CASCADE");
    }

    @Test
    void shouldOnboardActiveMerchantWithOwnerMembership() {
        long userId = insertUser("usr_onboarding", "onboarding@example.com");

        MerchantOnboardingApi.Result result = onboardingApi.onboard(
                new MerchantOnboardingApi.Command(userId, " ABC Store ")
        );

        assertThat(result.merchantPublicId()).startsWith("mrc_").hasSize(30);
        assertThat(result.merchantName()).isEqualTo("ABC Store");
        assertThat(result.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(result.initialRole()).isEqualTo(MerchantRole.OWNER);

        Merchant merchant = merchantRepository.findByPublicId(result.merchantPublicId()).orElseThrow();
        MerchantMember owner = memberRepository
                .findByMerchantIdAndUserId(merchant.id(), userId)
                .orElseThrow();

        assertThat(merchant.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(owner.role()).isEqualTo(MerchantRole.OWNER);
        assertThat(owner.userId()).isEqualTo(userId);
    }

    @Test
    void shouldPersistAndReloadMerchantMapping() {
        Merchant saved = merchantRepository.save(Merchant.create(
                "mrc_mapping",
                "Mapping Store",
                CREATED_AT
        ));

        Merchant reloaded = merchantRepository.findByPublicId(saved.publicId()).orElseThrow();

        assertThat(saved.id()).isPositive();
        assertThat(reloaded.id()).isEqualTo(saved.id());
        assertThat(reloaded.publicId()).isEqualTo("mrc_mapping");
        assertThat(reloaded.name()).isEqualTo("Mapping Store");
        assertThat(reloaded.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(reloaded.version()).isZero();
        assertThat(reloaded.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void shouldRejectDuplicateMembership() {
        long userId = insertUser("usr_duplicate_member", "duplicate-member@example.com");
        Merchant merchant = merchantRepository.save(Merchant.create(
                "mrc_duplicate_member",
                "Duplicate Store",
                CREATED_AT
        ));
        MerchantMember owner = MerchantMember.createOwner(merchant.id(), userId, CREATED_AT);
        memberRepository.add(owner);

        assertThatThrownBy(() -> memberRepository.add(owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long insertUser(String publicId, String email) {
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (
                    public_id, email, password_hash, first_name, last_name,
                    status, created_at, updated_at, version
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
                RETURNING id
                """,
                Long.class,
                publicId,
                email,
                "$2a$10$merchant-test-hash",
                "Viet",
                "Nguyen",
                CREATED_AT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
        );
        return id;
    }
}
