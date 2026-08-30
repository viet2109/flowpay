package com.flowpay.backend.identity.application;

public interface AuthSessionUseCase {

    LoginResult login(LoginCommand command);

    RefreshResult refresh(String rawRefreshToken);

    void logout(String rawRefreshToken);
}
