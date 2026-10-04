package com.flowpay.backend.infrastructure.messaging.outbox;

import com.flowpay.backend.payment.application.event.PaymentFailedEventV1;
import com.flowpay.backend.payment.application.event.PaymentIntegrationEventPublisher;
import com.flowpay.backend.payment.application.event.PaymentProcessingEventV1;
import com.flowpay.backend.refund.application.event.RefundFailedEventV1;
import com.flowpay.backend.refund.application.event.RefundIntegrationEventPublisher;
import com.flowpay.backend.refund.application.event.RefundProcessingEventV1;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class SourceEventContractTest {
    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");

    @ParameterizedTest
    @MethodSource("contracts")
    void normalizesScalarContractsWithoutChangingIdentityOrOccurrence(Contract contract) throws Exception {
        Object event = construct(contract.type(), contract.arguments());
        assertThat(contract.type().getMethod("eventType").invoke(event)).isEqualTo(contract.eventType());
        assertThat(contract.type().getMethod("aggregateType").invoke(event))
                .isEqualTo(contract.refund() ? "REFUND" : "PAYMENT_INTENT");
        assertThat(contract.type().getMethod("aggregateId").invoke(event))
                .isEqualTo(contract.refund() ? "re_contract" : "pi_contract");
        assertThat(contract.type().getMethod("currency").invoke(event)).isEqualTo("VND");
        assertThat(contract.type().getMethod("occurredAt").invoke(event)).isEqualTo(NOW);
        List<String> fields = Arrays.stream(contract.type().getRecordComponents())
                .map(RecordComponent::getName).toList();
        var expected = new java.util.ArrayList<>(List.of("merchantInternalId"));
        if (contract.refund()) expected.add("refundPublicId");
        expected.addAll(List.of("paymentPublicId", "amountMinor", "currency"));
        if (contract.failed()) expected.addAll(List.of("failureCode", "failureMessage"));
        expected.add("occurredAt");
        assertThat(fields).containsExactlyElementsOf(expected);
        if (contract.failed()) {
            assertThat(contract.type().getMethod("failureCode").invoke(event)).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(contract.type().getMethod("failureMessage").invoke(event)).isEqualTo("Operation failed.");
        }
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void rejectsInvalidMoneyOwnershipCurrencyAndFailureMetadata(Contract contract) {
        for (int index : List.of(0, contract.refund() ? 3 : 2, contract.refund() ? 4 : 3)) {
            Object[] arguments = contract.arguments().clone();
            arguments[index] = index == 0 ? 0L : index == (contract.refund() ? 3 : 2) ? -1L : "invalid";
            assertThatThrownBy(() -> construct(contract.type(), arguments))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        if (contract.failed()) {
            for (int index : List.of(contract.refund() ? 5 : 4, contract.refund() ? 6 : 5)) {
                Object[] arguments = contract.arguments().clone();
                arguments[index] = "   ";
                assertThatThrownBy(() -> construct(contract.type(), arguments))
                        .hasCauseInstanceOf(IllegalArgumentException.class);
            }
        }
        Object[] arguments = contract.arguments().clone();
        arguments[arguments.length - 1] = null;
        assertThatThrownBy(() -> construct(contract.type(), arguments)).hasCauseInstanceOf(NullPointerException.class);
    }

    @Test
    void publisherPortsHaveThreeExplicitTypedOverloadsRatherThanGenericPublish() {
        for (Class<?> port : List.of(PaymentIntegrationEventPublisher.class, RefundIntegrationEventPublisher.class)) {
            assertThat(port.isAnnotationPresent(FunctionalInterface.class)).isFalse();
            assertThat(port.getDeclaredMethods()).hasSize(3).allSatisfy(method -> {
                assertThat(method.getName()).isEqualTo("publish");
                assertThat(method.getReturnType()).isEqualTo(void.class);
                assertThat(method.getParameterTypes()).hasSize(1);
                assertThat(method.getParameterTypes()[0].isRecord()).isTrue();
            });
        }
    }

    private static Object construct(Class<?> type, Object[] arguments) throws Exception {
        Class<?>[] parameterTypes = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType).toArray(Class<?>[]::new);
        return type.getConstructor(parameterTypes).newInstance(arguments);
    }

    private static Stream<Contract> contracts() {
        return Stream.of(
                new Contract(PaymentProcessingEventV1.class, "payment.processing.v1", false, false,
                        new Object[]{7L, " pi_contract ", 1000L, " vnd ", NOW}),
                new Contract(PaymentFailedEventV1.class, "payment.failed.v1", false, true,
                        new Object[]{7L, " pi_contract ", 1000L, " vnd ", " PROVIDER_UNAVAILABLE ", " Operation failed. ", NOW}),
                new Contract(RefundProcessingEventV1.class, "refund.processing.v1", true, false,
                        new Object[]{7L, " re_contract ", " pi_contract ", 400L, " vnd ", NOW}),
                new Contract(RefundFailedEventV1.class, "refund.failed.v1", true, true,
                        new Object[]{7L, " re_contract ", " pi_contract ", 400L, " vnd ",
                                " PROVIDER_UNAVAILABLE ", " Operation failed. ", NOW})
        );
    }

    private record Contract(Class<?> type, String eventType, boolean refund, boolean failed, Object[] arguments) {
    }
}
