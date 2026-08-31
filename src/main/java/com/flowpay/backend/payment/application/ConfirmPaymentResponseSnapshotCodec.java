package com.flowpay.backend.payment.application;

public interface ConfirmPaymentResponseSnapshotCodec {

    String encode(ConfirmPaymentResponseSnapshot snapshot);

    ConfirmPaymentResponseSnapshot decode(String payload);
}
