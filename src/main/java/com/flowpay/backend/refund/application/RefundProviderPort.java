package com.flowpay.backend.refund.application;

public interface RefundProviderPort {

    RefundProviderResult refund(RefundProviderRequest request);
}
