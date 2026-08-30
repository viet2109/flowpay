package com.flowpay.backend.identity.application;

import com.flowpay.backend.identity.domain.RefreshToken;

import java.util.Optional;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);
}
