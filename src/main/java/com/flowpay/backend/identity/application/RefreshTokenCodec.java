package com.flowpay.backend.identity.application;

public interface RefreshTokenCodec {

    String generate();

    String digest(String rawToken);

    boolean hasValidFormat(String rawToken);
}
