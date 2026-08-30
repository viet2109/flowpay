package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentMerchantResolverTest {

    @Mock
    private MerchantAccessApi merchantAccessApi;

    @InjectMocks
    private PaymentMerchantResolver resolver;

    @Test
    void shouldResolveActiveMerchantFromAuthenticatedPrincipal() {
        MerchantApiPrincipal principal = principal("mrc_active");
        ActiveMerchantSnapshot snapshot = new ActiveMerchantSnapshot(41L, "mrc_active");
        when(merchantAccessApi.requireActiveMerchant("mrc_active")).thenReturn(snapshot);

        ActiveMerchantSnapshot resolved = resolver.resolve(principal);

        assertThat(resolved).isEqualTo(snapshot);
        verify(merchantAccessApi).requireActiveMerchant(principal.merchantPublicId());
    }

    @Test
    void shouldPropagateSuspendedAndClosedMerchantRejection() {
        for (String merchantPublicId : new String[]{"mrc_suspended", "mrc_closed"}) {
            ApiException inactive = new ApiException(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.MERCHANT_SUSPENDED,
                    "The merchant is not active."
            );
            when(merchantAccessApi.requireActiveMerchant(merchantPublicId)).thenThrow(inactive);

            assertThatThrownBy(() -> resolver.resolve(principal(merchantPublicId)))
                    .isSameAs(inactive);
        }
    }

    @Test
    void shouldRejectMissingPrincipalBeforeMerchantResolution() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("principal must not be null");

        verifyNoInteractions(merchantAccessApi);
    }

    private static MerchantApiPrincipal principal(String merchantPublicId) {
        return new MerchantApiPrincipal(merchantPublicId, "key_payment_access");
    }
}
