package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentIntent;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransaction;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentQueryServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-30T10:00:00Z");
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_query_owner",
            "key_query_owner"
    );

    @Mock
    private PaymentMerchantResolver merchantResolver;

    @Mock
    private PaymentOwnershipService ownershipService;

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    @InjectMocks
    private PaymentQueryService service;

    @Test
    void shouldReturnSafePaymentDetail() {
        PaymentIntent payment = succeededPayment();
        when(ownershipService.requireOwnedPayment(PRINCIPAL, "pi_query"))
                .thenReturn(payment);

        PaymentIntentView result = service.get(PRINCIPAL, "pi_query");

        assertThat(result).isEqualTo(new PaymentIntentView(
                "pi_query",
                "ORDER-QUERY",
                "Query payment",
                10_000L,
                "USD",
                PaymentStatus.SUCCEEDED,
                0L,
                10_000L,
                CREATED_AT,
                CREATED_AT.plusSeconds(2)
        ));
    }

    @Test
    void shouldResolveMerchantAndPassNormalizedFiltersToRepository() {
        SearchPaymentIntentsQuery query = new SearchPaymentIntentsQuery(
                PRINCIPAL,
                PaymentStatus.SUCCEEDED,
                " ORDER-QUERY ",
                CREATED_AT.minusSeconds(1),
                CREATED_AT.plusSeconds(1),
                1,
                10
        );
        when(merchantResolver.resolve(PRINCIPAL))
                .thenReturn(new ActiveMerchantSnapshot(41L, "mrc_query_owner"));
        when(paymentIntentRepository.search(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentIntentPage(List.of(succeededPayment()), 1, 10, 11, 2, false, true));

        PaymentIntentViewPage result = service.search(query);

        ArgumentCaptor<PaymentIntentSearchCriteria> criteria =
                ArgumentCaptor.forClass(PaymentIntentSearchCriteria.class);
        verify(paymentIntentRepository).search(criteria.capture());
        assertThat(criteria.getValue()).isEqualTo(new PaymentIntentSearchCriteria(
                41L,
                PaymentStatus.SUCCEEDED,
                "ORDER-QUERY",
                CREATED_AT.minusSeconds(1),
                CREATED_AT.plusSeconds(1),
                1,
                10
        ));
        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(11);
        assertThat(result.hasPrevious()).isTrue();
    }

    @Test
    void shouldRejectInvalidPaginationAndDateRangeBeforeResolvingMerchant() {
        assertInvalidQuery(query(0, 101, null, null), "size must be between 1 and 100");
        assertInvalidQuery(query(-1, 20, null, null), "page must not be negative");
        assertInvalidQuery(
                query(0, 20, CREATED_AT.plusSeconds(1), CREATED_AT),
                "createdFrom must not be after createdTo"
        );

        verifyNoInteractions(merchantResolver, paymentIntentRepository);
    }

    @Test
    void shouldReturnNormalizedTransactionHistoryForOwnedPayment() {
        PaymentIntent payment = succeededPayment();
        PaymentTransaction transaction = PaymentTransaction.rehydrate(
                91L,
                "ptxn_query",
                payment.internalId(),
                1,
                "SIMULATOR",
                "provider-safe-reference",
                PaymentTransactionStatus.FAILED,
                "CARD_DECLINED",
                "The payment was declined.",
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2),
                0L
        );
        when(ownershipService.requireOwnedPayment(PRINCIPAL, "pi_query"))
                .thenReturn(payment);
        when(paymentTransactionRepository.findByPaymentIntentId(payment.internalId()))
                .thenReturn(List.of(transaction));

        List<PaymentTransactionView> result = service.transactionHistory(PRINCIPAL, "pi_query");

        assertThat(result).containsExactly(new PaymentTransactionView(
                "ptxn_query",
                1,
                "SIMULATOR",
                "provider-safe-reference",
                PaymentTransactionStatus.FAILED,
                "CARD_DECLINED",
                "The payment was declined.",
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2)
        ));
    }

    private static SearchPaymentIntentsQuery query(
            int page,
            int size,
            Instant createdFrom,
            Instant createdTo
    ) {
        return new SearchPaymentIntentsQuery(
                PRINCIPAL,
                null,
                null,
                createdFrom,
                createdTo,
                page,
                size
        );
    }

    private void assertInvalidQuery(SearchPaymentIntentsQuery query, String message) {
        assertThatThrownBy(() -> service.search(query)).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(exception.status().value()).isEqualTo(400);
            assertThat(exception.getMessage()).isEqualTo(message);
        });
    }

    private static PaymentIntent succeededPayment() {
        return PaymentIntent.rehydrate(
                81L,
                "pi_query",
                41L,
                "ORDER-QUERY",
                "Query payment",
                Money.of(10_000L, "USD"),
                PaymentStatus.SUCCEEDED,
                Money.of(0L, "USD"),
                Money.of(0L, "USD"),
                1L,
                CREATED_AT,
                CREATED_AT.plusSeconds(2)
        );
    }
}
