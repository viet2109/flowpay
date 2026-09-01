package com.flowpay.backend.ledger.application;

public class LedgerPostingException extends RuntimeException {

    LedgerPostingException(String message) {
        super(message);
    }

    LedgerPostingException(String message, Throwable cause) {
        super(message, cause);
    }
}
