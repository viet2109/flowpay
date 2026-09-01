package com.flowpay.backend.ledger.application;

public final class LedgerPostingConflictException extends LedgerPostingException {

    LedgerPostingConflictException(String message) {
        super(message);
    }
}
