package com.flowpay.backend.ledger.application;

public final class LedgerAccountProvisioningException extends RuntimeException {

    LedgerAccountProvisioningException(String message) {
        super(message);
    }

    LedgerAccountProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
