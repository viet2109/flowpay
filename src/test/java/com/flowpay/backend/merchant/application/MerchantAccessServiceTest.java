package com.flowpay.backend.merchant.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.domain.Merchant;
import com.flowpay.backend.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantAccessServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T04:00:00Z");
    private static final String MERCHANT_PUBLIC_ID = "mrc_payment_owner";

    @Mock
    private MerchantRepository merchantRepository;

    @InjectMocks
    private MerchantAccessService service;

    @Test
    void shouldReturnMinimalSnapshotForActiveMerchant() {
        Merchant merchant = merchant(MerchantStatus.ACTIVE);
        when(merchantRepository.findByPublicId(MERCHANT_PUBLIC_ID)).thenReturn(Optional.of(merchant));

        ActiveMerchantSnapshot snapshot = service.requireActiveMerchant(MERCHANT_PUBLIC_ID);

        assertThat(snapshot).isEqualTo(new ActiveMerchantSnapshot(41L, MERCHANT_PUBLIC_ID));
        assertThat(snapshot.toString()).doesNotContain("Merchant[").doesNotContain("version");
        verify(merchantRepository).findByPublicId(MERCHANT_PUBLIC_ID);
    }

    @Test
    void shouldRejectSuspendedAndClosedMerchants() {
        for (MerchantStatus status : new MerchantStatus[]{MerchantStatus.SUSPENDED, MerchantStatus.CLOSED}) {
            when(merchantRepository.findByPublicId(MERCHANT_PUBLIC_ID))
                    .thenReturn(Optional.of(merchant(status)));

            assertThatThrownBy(() -> service.requireActiveMerchant(MERCHANT_PUBLIC_ID))
                    .isInstanceOfSatisfying(ApiException.class, exception -> {
                        assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(exception.code()).isEqualTo(ErrorCode.MERCHANT_SUSPENDED);
                        assertThat(exception.getMessage()).isEqualTo("The merchant is not active.");
                    });
        }
    }

    @Test
    void shouldReturnNotFoundWithoutExposingRepositoryDetails() {
        when(merchantRepository.findByPublicId("mrc_missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireActiveMerchant("mrc_missing"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ErrorCode.MERCHANT_NOT_FOUND);
                    assertThat(exception.toString()).doesNotContain("SQL").doesNotContain("MerchantEntity");
                });
    }

    @Test
    void shouldRejectBlankPublicIdBeforeRepositoryLookup() {
        assertThatThrownBy(() -> service.requireActiveMerchant("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("merchantPublicId must not be blank");

        verifyNoInteractions(merchantRepository);
    }

    private static Merchant merchant(MerchantStatus status) {
        return Merchant.rehydrate(
                41L,
                MERCHANT_PUBLIC_ID,
                "Payment Store",
                status,
                2,
                CREATED_AT,
                CREATED_AT
        );
    }
}
