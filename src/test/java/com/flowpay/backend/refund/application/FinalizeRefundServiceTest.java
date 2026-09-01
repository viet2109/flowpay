package com.flowpay.backend.refund.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.application.LedgerPostingApi;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import com.flowpay.backend.payment.application.PaymentRefundApi;
import com.flowpay.backend.refund.domain.Refund;
import com.flowpay.backend.refund.domain.RefundProviderOutcome;
import com.flowpay.backend.refund.domain.RefundReason;
import com.flowpay.backend.refund.domain.RefundStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinalizeRefundServiceTest {

    private static final long MERCHANT_ID = 71L;
    private static final long PAYMENT_INTERNAL_ID = 41L;
    private static final String PAYMENT_PUBLIC_ID = "pi_finalize_unit";
    private static final String REFUND_PUBLIC_ID = "re_finalize_unit";
    private static final Money REFUND_AMOUNT = Money.of(400L, "USD");
    private static final Instant FINALIZED_AT = Instant.parse("2026-09-01T08:00:00Z");

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private PaymentRefundApi paymentRefundApi;

    @Mock
    private LedgerPostingApi ledgerPostingApi;

    private FinalizeRefundService service;

    @BeforeEach
    void setUp() {
        service = new FinalizeRefundService(
                refundRepository,
                paymentRefundApi,
                ledgerPostingApi,
                Clock.fixed(FINALIZED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void successShouldPostExactRefundFactsAfterPaymentAndRefundMutation() {
        Refund refund = processingRefund();
        stubRepository(refund);

        FinalizedRefund finalized = service.finalizeRefund(command(success()));

        PostRefundSucceededCommand expectedPosting = new PostRefundSucceededCommand(
                MERCHANT_ID,
                REFUND_PUBLIC_ID,
                REFUND_AMOUNT,
                FINALIZED_AT
        );
        InOrder order = inOrder(paymentRefundApi, refundRepository, ledgerPostingApi);
        order.verify(paymentRefundApi).completeRefund(
                MERCHANT_ID,
                PAYMENT_PUBLIC_ID,
                PAYMENT_INTERNAL_ID,
                REFUND_AMOUNT
        );
        order.verify(refundRepository).save(refund);
        order.verify(ledgerPostingApi).postRefundSucceeded(expectedPosting);
        assertThat(finalized.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(finalized.completedAt()).isEqualTo(FINALIZED_AT);
    }

    @ParameterizedTest
    @EnumSource(
            value = RefundProviderOutcome.class,
            names = {"DECLINED", "TECHNICAL_FAILURE", "UNKNOWN"}
    )
    void nonSuccessOutcomeShouldNeverPostLedger(RefundProviderOutcome outcome) {
        Refund refund = processingRefund();
        stubRepository(refund);
        RefundProviderResult result = new RefundProviderResult(
                "SIMULATOR",
                outcome,
                null,
                "PROVIDER_RESULT",
                "Provider did not confirm success."
        );

        service.finalizeRefund(command(result));

        verify(ledgerPostingApi, never()).postRefundSucceeded(any());
    }

    @Test
    void ledgerFailureShouldPropagateFromFinalizeBoundary() {
        Refund refund = processingRefund();
        stubRepository(refund);
        when(ledgerPostingApi.postRefundSucceeded(any()))
                .thenThrow(new IllegalStateException("forced ledger failure"));

        assertThatThrownBy(() -> service.finalizeRefund(command(success())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced ledger failure");
    }

    private void stubRepository(Refund refund) {
        when(refundRepository.findByPublicIdAndMerchantIdForUpdate(
                REFUND_PUBLIC_ID,
                MERCHANT_ID
        )).thenReturn(Optional.of(refund));
        when(refundRepository.save(refund)).thenReturn(refund);
    }

    private static Refund processingRefund() {
        return Refund.rehydrate(
                11L,
                REFUND_PUBLIC_ID,
                MERCHANT_ID,
                PAYMENT_INTERNAL_ID,
                REFUND_AMOUNT,
                RefundStatus.PROCESSING,
                RefundReason.of("Customer request"),
                "SIMULATOR",
                null,
                null,
                null,
                FINALIZED_AT.minusSeconds(10),
                FINALIZED_AT.minusSeconds(5),
                null,
                0L
        );
    }

    private static FinalizeRefundCommand command(RefundProviderResult providerResult) {
        return new FinalizeRefundCommand(
                MERCHANT_ID,
                REFUND_PUBLIC_ID,
                PAYMENT_PUBLIC_ID,
                providerResult
        );
    }

    private static RefundProviderResult success() {
        return new RefundProviderResult(
                "SIMULATOR",
                RefundProviderOutcome.SUCCESS,
                "provider_refund_unit",
                null,
                null
        );
    }
}
