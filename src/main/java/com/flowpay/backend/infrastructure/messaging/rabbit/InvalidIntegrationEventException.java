package com.flowpay.backend.infrastructure.messaging.rabbit;

public class InvalidIntegrationEventException extends RuntimeException {

    public InvalidIntegrationEventException(String message) {
        super(message);
    }

    public InvalidIntegrationEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
