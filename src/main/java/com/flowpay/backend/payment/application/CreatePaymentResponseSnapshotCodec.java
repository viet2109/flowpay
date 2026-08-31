package com.flowpay.backend.payment.application;

public interface CreatePaymentResponseSnapshotCodec {

    String encode(CreatePaymentResponseSnapshot snapshot);

    CreatePaymentResponseSnapshot decode(String payload);
}
