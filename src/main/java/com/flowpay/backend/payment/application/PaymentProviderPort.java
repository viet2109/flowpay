package com.flowpay.backend.payment.application;

public interface PaymentProviderPort {

    PaymentProviderResult charge(PaymentProviderRequest request);
}
