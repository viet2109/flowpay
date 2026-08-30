package com.flowpay.backend.identity.infrastructure.persistence;

import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.PasswordHash;
import com.flowpay.backend.identity.domain.User;

final class UserPersistenceMapper {

    private UserPersistenceMapper() {
    }

    static UserEntity toEntity(User user) {
        return new UserEntity(
                user.id(),
                user.publicId(),
                user.email().value(),
                user.passwordHash().value(),
                user.firstName(),
                user.lastName(),
                user.status(),
                user.createdAt(),
                user.updatedAt(),
                user.version()
        );
    }

    static User toDomain(UserEntity entity) {
        return User.rehydrate(
                entity.id(),
                entity.publicId(),
                Email.of(entity.email()),
                PasswordHash.of(entity.passwordHash()),
                entity.firstName(),
                entity.lastName(),
                entity.status(),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
