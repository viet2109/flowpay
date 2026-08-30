package com.flowpay.backend.payment.application;

import java.util.List;

public record PaymentIntentViewPage(
        List<PaymentIntentView> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public PaymentIntentViewPage {
        content = List.copyOf(content);
    }
}
