package com.flowpay.backend.payment.infrastructure.provider;

import com.flowpay.backend.payment.application.PaymentProviderPort;
import com.flowpay.backend.payment.application.PaymentProviderRequest;
import com.flowpay.backend.payment.application.PaymentProviderResult;
import com.flowpay.backend.payment.domain.ProviderOutcome;

import java.util.Objects;

public final class SimulatorPaymentProvider implements PaymentProviderPort {

    static final String PROVIDER = "SIMULATOR";
    static final String DECLINED_CODE = "CARD_DECLINED";
    static final String DECLINED_MESSAGE = "The provider declined the payment.";
    static final String UNKNOWN_CODE = "PROVIDER_TIMEOUT";
    static final String UNKNOWN_MESSAGE = "The provider outcome is unknown.";
    static final String TECHNICAL_FAILURE_CODE = "PROVIDER_UNAVAILABLE";
    static final String TECHNICAL_FAILURE_MESSAGE =
            "The provider operation did not complete.";

    static final long TRIGGER_DECLINED = 901;
    static final long TRIGGER_UNKNOWN = 902;
    static final long TRIGGER_TECHNICAL_FAILURE = 903;

    private final ProviderOutcome defaultOutcome;
    private final boolean amountTriggersEnabled;

    SimulatorPaymentProvider(ProviderOutcome defaultOutcome) {
        this(defaultOutcome, false);
    }

    SimulatorPaymentProvider(ProviderOutcome defaultOutcome, boolean amountTriggersEnabled) {
        this.defaultOutcome = Objects.requireNonNull(
                defaultOutcome,
                "defaultOutcome must not be null"
        );
        this.amountTriggersEnabled = amountTriggersEnabled;
    }

    @Override
    public PaymentProviderResult charge(PaymentProviderRequest request) {
        PaymentProviderRequest providerRequest = Objects.requireNonNull(
                request,
                "request must not be null"
        );
        return switch (outcomeFor(providerRequest)) {
            case SUCCESS -> success(providerRequest);
            case DECLINED -> declined(providerRequest);
            case UNKNOWN -> unknown();
            case TECHNICAL_FAILURE -> technicalFailure();
        };
    }

    /**
     * Development-only test triggers, as in PSP sandboxes: when enabled, the last
     * three digits of the amount in minor units select a non-success outcome, so
     * every outcome is reachable without restarting the process. The result stays
     * a pure function of the request, so replays remain deterministic.
     */
    private ProviderOutcome outcomeFor(PaymentProviderRequest request) {
        if (!amountTriggersEnabled) {
            return defaultOutcome;
        }
        long trigger = request.amountMinor() % 1000;
        if (trigger == TRIGGER_DECLINED) {
            return ProviderOutcome.DECLINED;
        }
        if (trigger == TRIGGER_UNKNOWN) {
            return ProviderOutcome.UNKNOWN;
        }
        if (trigger == TRIGGER_TECHNICAL_FAILURE) {
            return ProviderOutcome.TECHNICAL_FAILURE;
        }
        return defaultOutcome;
    }

    private static PaymentProviderResult success(PaymentProviderRequest request) {
        return new PaymentProviderResult(
                PROVIDER,
                ProviderOutcome.SUCCESS,
                transactionId(request),
                null,
                null
        );
    }

    private static PaymentProviderResult declined(PaymentProviderRequest request) {
        return new PaymentProviderResult(
                PROVIDER,
                ProviderOutcome.DECLINED,
                transactionId(request),
                DECLINED_CODE,
                DECLINED_MESSAGE
        );
    }

    private static PaymentProviderResult unknown() {
        return new PaymentProviderResult(
                PROVIDER,
                ProviderOutcome.UNKNOWN,
                null,
                UNKNOWN_CODE,
                UNKNOWN_MESSAGE
        );
    }

    private static PaymentProviderResult technicalFailure() {
        return new PaymentProviderResult(
                PROVIDER,
                ProviderOutcome.TECHNICAL_FAILURE,
                null,
                TECHNICAL_FAILURE_CODE,
                TECHNICAL_FAILURE_MESSAGE
        );
    }

    private static String transactionId(PaymentProviderRequest request) {
        return "sim_" + request.paymentPublicReference();
    }
}
