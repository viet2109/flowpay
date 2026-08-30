package com.flowpay.backend.identity.application;

public interface AccessTokenIssuer {

    IssuedAccessToken issue(AuthenticatedIdentity identity);
}
