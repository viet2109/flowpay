package com.flowpay.backend.webhook.domain;

/** Storage bound, not secret sanitization: application callers must supply normalized diagnostics. */
final class WebhookDiagnostic {
    static final int MAX_LENGTH = 512;

    private WebhookDiagnostic() { }

    static String bounded(String error) {
        if (error == null || error.isBlank()) return null;
        String normalized = error.trim();
        // Avoid splitting a surrogate pair when truncating a diagnostic.
        int end = Math.min(MAX_LENGTH, normalized.length());
        if (end < normalized.length() && Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end);
    }
}
