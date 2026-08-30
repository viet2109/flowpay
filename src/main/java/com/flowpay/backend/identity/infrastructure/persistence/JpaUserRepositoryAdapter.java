package com.flowpay.backend.identity.infrastructure.persistence;

import com.flowpay.backend.identity.application.UserRepository;
import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaUserRepositoryAdapter implements UserRepository {

    private final SpringDataUserRepository repository;

    @Override
    public User save(User user) {
        UserEntity saved = repository.saveAndFlush(UserPersistenceMapper.toEntity(user));
        return UserPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<User> findById(long id) {
        return repository.findById(id).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public Optional<User> findByPublicId(String publicId) {
        return repository.findByPublicId(publicId).map(UserPersistenceMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return repository.findByEmailIgnoreCase(email.value()).map(UserPersistenceMapper::toDomain);
    }
}
