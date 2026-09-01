package com.flowpay.backend.refund.application;

import com.flowpay.backend.refund.domain.Refund;

import java.util.List;

public record RefundPage(
        List<Refund> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public RefundPage {
        content = List.copyOf(content);
    }
}
