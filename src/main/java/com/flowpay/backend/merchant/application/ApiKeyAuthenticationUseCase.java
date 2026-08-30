package com.flowpay.backend.merchant.application;

public interface ApiKeyAuthenticationUseCase {

    AuthenticatedApiKey authenticate(String rawApiKey);
}
