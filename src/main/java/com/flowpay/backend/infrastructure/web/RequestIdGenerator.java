package com.flowpay.backend.infrastructure.web;

import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
public class RequestIdGenerator {

    public String generate() {
        return "req_" + UlidCreator.getMonotonicUlid();
    }
}
