package com.flowpay.backend.identity.application;

public interface PasswordAuthenticationUseCase {

    AuthenticatedIdentity authenticate(LoginCommand command);
}
