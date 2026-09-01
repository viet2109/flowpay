package com.flowpay.backend.idempotency.application;

/**
 * Semantic, versioned input that defines its canonical fingerprint fields.
 */
public interface RequestFingerprintInput {

    void appendTo(RequestFingerprintCanonicalizer canonicalizer);
}
