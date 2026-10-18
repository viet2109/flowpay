package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.common.observability.ExternalCallTracing;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfirmPaymentService {

    private final PreparePaymentConfirmationService preparationService;
    private final PaymentProviderPort paymentProvider;
    private final FinalizePaymentConfirmationService finalizationService;
    private final PaymentMetrics metrics;
    private final ExternalCallTracing tracing;

    public FinalizedPaymentConfirmation confirm(ConfirmPaymentCommand command) {
        return executePrepared(prepare(command));
    }

    PreparedPaymentConfirmation prepare(ConfirmPaymentCommand command) {
        return preparationService.prepare(
                new PreparePaymentConfirmationCommand(
                        command.merchantContext(),
                        command.paymentPublicId()
                )
        );
    }

    FinalizedPaymentConfirmation executePrepared(PreparedPaymentConfirmation prepared) {
        PaymentProviderResult providerResult = metrics.measureProvider(() -> tracing.call(
                ExternalCallTracing.Operation.PAYMENT_PROVIDER,
                () -> paymentProvider.charge(new PaymentProviderRequest(
                        prepared.paymentPublicId(), Money.of(prepared.amountMinor(), prepared.currency())
                )),
                result -> ExternalCallTracing.Outcome.valueOf(result.outcome().name())
        ));

        return finalizationService.finalizeConfirmation(
                new FinalizePaymentConfirmationCommand(
                        prepared.paymentPublicId(),
                        prepared.transactionPublicId(),
                        providerResult
                )
        );
    }
}
