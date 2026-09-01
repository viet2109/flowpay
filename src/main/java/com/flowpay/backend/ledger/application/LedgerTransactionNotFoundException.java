package com.flowpay.backend.ledger.application;

public final class LedgerTransactionNotFoundException extends LedgerReversalException {

    LedgerTransactionNotFoundException(String message) {
        super(message);
    }
}
