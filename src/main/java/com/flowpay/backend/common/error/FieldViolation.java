package com.flowpay.backend.common.error;

public record FieldViolation(String field, String code, String message) {
}
