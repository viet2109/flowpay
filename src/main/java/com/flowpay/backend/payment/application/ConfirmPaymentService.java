package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.money.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfirmPaymentService {

    private final PreparePaymentConfirmationService preparationService;
    private final PaymentProviderPort paymentProvider;
    private final FinalizePaymentConfirmationService finalizationService;

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
        PaymentProviderResult providerResult = paymentProvider.charge(
                new PaymentProviderRequest(
                        prepared.paymentPublicId(),
                        Money.of(prepared.amountMinor(), prepared.currency())
                )
        );

        return finalizationService.finalizeConfirmation(
                new FinalizePaymentConfirmationCommand(
                        prepared.paymentPublicId(),
                        prepared.transactionPublicId(),
                        providerResult
                )
        );
    }
}
