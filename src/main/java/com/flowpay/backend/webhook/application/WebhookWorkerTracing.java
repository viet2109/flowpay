package com.flowpay.backend.webhook.application;

import java.util.function.Supplier;

/** Optional technical observation; callbacks execute once and keep their original result/failure. */
public interface WebhookWorkerTracing {
    boolean attempt(long deliveryId, Supplier<Boolean> work);
    boolean recovery(long deliveryId, Supplier<Boolean> work);
}
