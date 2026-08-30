package com.flowpay.backend.infrastructure.security;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

record JwtRsaKeyPair(RSAPublicKey publicKey, RSAPrivateKey privateKey) {

    JwtRsaKeyPair {
        Objects.requireNonNull(publicKey, "publicKey must not be null");
        Objects.requireNonNull(privateKey, "privateKey must not be null");
    }
}
