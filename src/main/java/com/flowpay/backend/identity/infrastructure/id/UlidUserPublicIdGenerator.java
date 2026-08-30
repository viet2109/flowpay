package com.flowpay.backend.identity.infrastructure.id;

import com.flowpay.backend.identity.application.UserPublicIdGenerator;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
final class UlidUserPublicIdGenerator implements UserPublicIdGenerator {

    @Override
    public String nextId() {
        return "usr_" + UlidCreator.getMonotonicUlid();
    }
}
