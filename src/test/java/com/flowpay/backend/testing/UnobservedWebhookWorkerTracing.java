package com.flowpay.backend.testing;

import com.flowpay.backend.webhook.application.WebhookWorkerTracing;
import java.util.function.Supplier;

/** Focused orchestration fixtures deliberately exclude telemetry; wiring is tested separately. */
public final class UnobservedWebhookWorkerTracing implements WebhookWorkerTracing {
    @Override public boolean attempt(long id, Supplier<Boolean> work) { return work.get(); }
    @Override public boolean recovery(long id, Supplier<Boolean> work) { return work.get(); }
}
