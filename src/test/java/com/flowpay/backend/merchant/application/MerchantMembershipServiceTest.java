package com.flowpay.backend.merchant.application;

import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantMember;
import com.flowpay.backend.merchant.domain.MerchantRole;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MerchantMembershipServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T03:00:00Z");

    @Mock
    private MerchantMemberRepository memberRepository;

    @Mock
    private MerchantRepository merchantRepository;

    private MerchantMembershipService service;

    @BeforeEach
    void setUp() {
        service = new MerchantMembershipService(memberRepository, merchantRepository);
    }

    @Test
    void shouldResolvePublicMerchantMembershipWithoutExposingMerchantEntity() {
        given(memberRepository.findFirstByUserId(11L)).willReturn(Optional.of(
                MerchantMember.rehydrate(21L, 11L, MerchantRole.OWNER, CREATED_AT)
        ));
        given(merchantRepository.findById(21L)).willReturn(Optional.of(
                Merchant.rehydrate(
                        21L,
                        "mrc_01KVALID",
                        "ABC Store",
                        MerchantStatus.ACTIVE,
                        0,
                        CREATED_AT,
                        CREATED_AT
                )
        ));

        assertThat(service.findForUser(11L)).contains(
                new MerchantMembershipApi.Membership("mrc_01KVALID", MerchantRole.OWNER)
        );
    }

    @Test
    void shouldReturnEmptyWhenUserHasNoMembership() {
        given(memberRepository.findFirstByUserId(11L)).willReturn(Optional.empty());

        assertThat(service.findForUser(11L)).isEmpty();
    }

    @Test
    void shouldRejectInvalidInternalUserReference() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.findForUser(0));
    }
}
