package com.flowpay.backend.payment.application;

import com.flowpay.backend.payment.domain.PaymentIntent;

import java.util.List;

public record PaymentIntentPage(
        List<PaymentIntent> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public PaymentIntentPage {
        content = List.copyOf(content);
    }
}
