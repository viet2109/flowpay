package com.flowpay.backend.refund.application;

public interface RefundResponseSnapshotCodec {

    String encode(RefundResponseSnapshot snapshot);

    RefundResponseSnapshot decode(String payload);
}
