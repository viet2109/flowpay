package com.flowpay.backend.identity.infrastructure.persistence;

import com.flowpay.backend.identity.application.RefreshTokenRepository;
import com.flowpay.backend.identity.domain.RefreshToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaRefreshTokenRepositoryAdapter implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository repository;

    @Override
    public RefreshToken save(RefreshToken token) {
        return toDomain(repository.saveAndFlush(toEntity(token)));
    }

    @Override
    public Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash) {
        return repository.findByTokenHashForUpdate(tokenHash).map(JpaRefreshTokenRepositoryAdapter::toDomain);
    }

    private static RefreshTokenEntity toEntity(RefreshToken token) {
        return new RefreshTokenEntity(
                token.id(),
                token.userId(),
                token.tokenHash(),
                token.expiresAt(),
                token.revokedAt(),
                token.createdAt(),
                token.lastUsedAt(),
                token.replacedById()
        );
    }

    private static RefreshToken toDomain(RefreshTokenEntity entity) {
        return RefreshToken.rehydrate(
                entity.id(),
                entity.userId(),
                entity.tokenHash(),
                entity.expiresAt(),
                entity.revokedAt(),
                entity.createdAt(),
                entity.lastUsedAt(),
                entity.replacedById()
        );
    }
}
