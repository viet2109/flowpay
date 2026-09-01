package com.flowpay.backend.refund.application;

import java.util.List;

public record RefundViewPage(
        List<RefundView> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public RefundViewPage {
        content = List.copyOf(content);
    }
}
