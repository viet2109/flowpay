package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import com.flowpay.backend.payment.domain.ProviderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmPaymentServiceTest {

    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_confirm",
            "key_confirm"
    );
    private static final ConfirmPaymentCommand COMMAND = new ConfirmPaymentCommand(
            PRINCIPAL,
            "pi_confirm"
    );
    private static final PreparedPaymentConfirmation PREPARED =
            new PreparedPaymentConfirmation(
                    "pi_confirm",
                    "ptxn_confirm",
                    50_000L,
                    "VND",
                    "SIMULATOR"
            );

    @Mock
    private PreparePaymentConfirmationService preparationService;

    @Mock
    private PaymentProviderPort paymentProvider;

    @Mock
    private FinalizePaymentConfirmationService finalizationService;

    private ConfirmPaymentService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmPaymentService(
                preparationService,
                paymentProvider,
                finalizationService
        );
    }

    @ParameterizedTest
    @EnumSource(ProviderOutcome.class)
    void shouldComposePreparationProviderAndFinalizationInOrder(ProviderOutcome outcome) {
        PaymentProviderResult providerResult = providerResult(outcome);
        FinalizedPaymentConfirmation finalized = finalized(outcome);
        PreparePaymentConfirmationCommand prepareCommand =
                new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_confirm");
        PaymentProviderRequest providerRequest = new PaymentProviderRequest(
                "pi_confirm",
                Money.of(50_000L, "VND")
        );
        FinalizePaymentConfirmationCommand finalizeCommand =
                new FinalizePaymentConfirmationCommand(
                        "pi_confirm",
                        "ptxn_confirm",
                        providerResult
                );
        when(preparationService.prepare(prepareCommand)).thenReturn(PREPARED);
        when(paymentProvider.charge(providerRequest)).thenReturn(providerResult);
        when(finalizationService.finalizeConfirmation(finalizeCommand)).thenReturn(finalized);

        FinalizedPaymentConfirmation result = service.confirm(COMMAND);

        assertThat(result).isEqualTo(finalized);
        InOrder invocationOrder = inOrder(
                preparationService,
                paymentProvider,
                finalizationService
        );
        invocationOrder.verify(preparationService).prepare(prepareCommand);
        invocationOrder.verify(paymentProvider).charge(providerRequest);
        invocationOrder.verify(finalizationService).finalizeConfirmation(finalizeCommand);
        invocationOrder.verifyNoMoreInteractions();
    }

    @Test
    void shouldNotCallProviderOrFinalizerWhenPreparationFails() {
        PreparePaymentConfirmationCommand prepareCommand =
                new PreparePaymentConfirmationCommand(PRINCIPAL, "pi_confirm");
        ApiException preparationFailure = new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.PAYMENT_INVALID_STATE,
                "The payment intent cannot be confirmed from its current state."
        );
        when(preparationService.prepare(prepareCommand)).thenThrow(preparationFailure);

        assertThatThrownBy(() -> service.confirm(COMMAND)).isSameAs(preparationFailure);

        verifyNoInteractions(paymentProvider, finalizationService);
    }

    @Test
    void orchestrationMustNotOpenADatabaseTransaction() throws NoSuchMethodException {
        assertThat(ConfirmPaymentService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(ConfirmPaymentService.class
                .getDeclaredMethod("confirm", ConfirmPaymentCommand.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(ConfirmPaymentService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(type -> type.contains("TransactionTemplate")
                        || type.contains("TransactionManager"));
    }

    private static PaymentProviderResult providerResult(ProviderOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    "sim_pi_confirm",
                    null,
                    null
            );
            case DECLINED -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    "sim_pi_confirm",
                    "CARD_DECLINED",
                    "The provider declined the payment."
            );
            case UNKNOWN -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
                    "PROVIDER_TIMEOUT",
                    "The provider outcome is unknown."
            );
            case TECHNICAL_FAILURE -> new PaymentProviderResult(
                    "SIMULATOR",
                    outcome,
                    null,
                    "PROVIDER_UNAVAILABLE",
                    "The provider operation did not complete."
            );
        };
    }

    private static FinalizedPaymentConfirmation finalized(ProviderOutcome outcome) {
        PaymentProviderResult providerResult = providerResult(outcome);
        PaymentStatus paymentStatus = switch (outcome) {
            case SUCCESS -> PaymentStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentStatus.FAILED;
            case UNKNOWN -> PaymentStatus.PROCESSING;
        };
        PaymentTransactionStatus transactionStatus = switch (outcome) {
            case SUCCESS -> PaymentTransactionStatus.SUCCEEDED;
            case DECLINED, TECHNICAL_FAILURE -> PaymentTransactionStatus.FAILED;
            case UNKNOWN -> PaymentTransactionStatus.UNKNOWN;
        };
        return new FinalizedPaymentConfirmation(
                "pi_confirm",
                paymentStatus,
                "ptxn_confirm",
                transactionStatus,
                providerResult.provider(),
                providerResult.providerTransactionId(),
                providerResult.failureCode(),
                providerResult.failureMessage()
        );
    }
}
