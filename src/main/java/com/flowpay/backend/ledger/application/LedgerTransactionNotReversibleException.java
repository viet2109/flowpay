package com.flowpay.backend.ledger.application;

public final class LedgerTransactionNotReversibleException extends LedgerReversalException {

    LedgerTransactionNotReversibleException(String message) {
        super(message);
    }
}
