package com.flowpay.backend.ledger.application;

public class LedgerReversalException extends RuntimeException {

    LedgerReversalException(String message) {
        super(message);
    }

    LedgerReversalException(String message, Throwable cause) {
        super(message, cause);
    }
}
