package com.flowpay.backend.ledger.application;

public interface LedgerPostingApi {

    LedgerPostingResult postPaymentSucceeded(PostPaymentSucceededCommand command);

    LedgerPostingResult postRefundSucceeded(PostRefundSucceededCommand command);
}
