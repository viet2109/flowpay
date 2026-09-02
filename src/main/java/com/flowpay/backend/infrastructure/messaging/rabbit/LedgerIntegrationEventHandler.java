package com.flowpay.backend.infrastructure.messaging.rabbit;

import com.flowpay.backend.ledger.application.LedgerPostingApi;
import com.flowpay.backend.ledger.application.LedgerPostingResult;
import com.flowpay.backend.ledger.application.PostPaymentSucceededCommand;
import com.flowpay.backend.ledger.application.PostRefundSucceededCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LedgerIntegrationEventHandler {

    private final LedgerPostingApi ledgerPostingApi;

    @Transactional
    public LedgerPostingResult handlePayment(PostPaymentSucceededCommand command) {
        LedgerPostingResult result = ledgerPostingApi.postPaymentSucceeded(command);
        return Objects.requireNonNull(result, "ledger posting result must not be null");
    }

    @Transactional
    public LedgerPostingResult handleRefund(PostRefundSucceededCommand command) {
        LedgerPostingResult result = ledgerPostingApi.postRefundSucceeded(command);
        return Objects.requireNonNull(result, "ledger posting result must not be null");
    }
}
