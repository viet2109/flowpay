package com.flowpay.backend.identity.application;

import com.flowpay.backend.identity.domain.Email;
import com.flowpay.backend.identity.domain.User;

import java.util.Optional;

public interface UserRepository {

    User save(User user);

    Optional<User> findByPublicId(String publicId);

    Optional<User> findByEmail(Email email);
}
