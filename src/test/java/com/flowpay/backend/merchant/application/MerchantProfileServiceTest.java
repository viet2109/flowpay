package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MerchantProfileServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T03:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-30T03:00:00Z");

    @Mock
    private MerchantRepository merchantRepository;

    private MerchantProfileService service;

    @BeforeEach
    void setUp() {
        service = new MerchantProfileService(
                merchantRepository,
                Clock.fixed(UPDATED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRetrieveProfileByPublicId() {
        Merchant merchant = merchant();
        given(merchantRepository.findByPublicId(merchant.publicId())).willReturn(Optional.of(merchant));

        MerchantProfile profile = service.get(merchant.publicId());

        assertThat(profile).isEqualTo(new MerchantProfile(
                merchant.publicId(),
                "ABC Store",
                MerchantStatus.ACTIVE,
                CREATED_AT
        ));
    }

    @Test
    void shouldUpdateOnlyMerchantSelectedByPublicId() {
        Merchant merchant = merchant();
        given(merchantRepository.findByPublicId(merchant.publicId())).willReturn(Optional.of(merchant));
        given(merchantRepository.save(merchant)).willReturn(merchant);

        MerchantProfile profile = service.updateName(new UpdateMerchantProfileCommand(
                merchant.publicId(),
                "ABC Technology Store"
        ));

        assertThat(profile.name()).isEqualTo("ABC Technology Store");
        assertThat(merchant.updatedAt()).isEqualTo(UPDATED_AT);
        verify(merchantRepository).findByPublicId(merchant.publicId());
        verify(merchantRepository).save(merchant);
    }

    @Test
    void shouldReturnMerchantNotFoundWithoutLeakingPersistenceDetails() {
        given(merchantRepository.findByPublicId("mrc_missing")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("mrc_missing"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ErrorCode.MERCHANT_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo("The merchant was not found.");
                });
    }

    private static Merchant merchant() {
        return Merchant.rehydrate(
                41L,
                "mrc_01K2P1T03TEST",
                "ABC Store",
                MerchantStatus.ACTIVE,
                3,
                CREATED_AT,
                CREATED_AT
        );
    }
}
