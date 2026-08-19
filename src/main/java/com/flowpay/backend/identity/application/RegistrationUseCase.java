package com.flowpay.backend.identity.application;

public interface RegistrationUseCase {

    RegistrationResult register(RegistrationCommand command);
}
