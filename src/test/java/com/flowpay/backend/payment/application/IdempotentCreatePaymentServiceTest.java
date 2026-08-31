package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.payment.domain.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentCreatePaymentServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-31T10:00:00Z");
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_idempotent_create",
            "key_idempotent_create"
    );
    private static final ActiveMerchantSnapshot MERCHANT = new ActiveMerchantSnapshot(
            41L,
            "mrc_idempotent_create"
    );

    @Mock
    private PaymentMerchantResolver merchantResolver;

    @Mock
    private IdempotencyAcquisitionService acquisitionService;

    @Mock
    private CreatePaymentIntentService createPaymentIntentService;

    @Mock
    private CreatePaymentResponseSnapshotMapper snapshotMapper;

    @Mock
    private CreatePaymentResponseSnapshotCodec snapshotCodec;

    @Mock
    private IdempotencyCompletionService completionService;

    private IdempotentCreatePaymentService service;

    @BeforeEach
    void setUp() {
        service = new IdempotentCreatePaymentService(
                merchantResolver,
                new RequestFingerprintService(),
                acquisitionService,
                createPaymentIntentService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
    }

    @Test
    void newRequestShouldCreateSnapshotAndCompleteExecution() {
        IdempotentCreatePaymentCommand command = command("checkout-new", 50_000L);
        CreatePaymentIntentResult created = createdResult();
        CreatePaymentResponseSnapshot snapshot = snapshot();
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                91L,
                null
        ));
        when(createPaymentIntentService.createForResolvedMerchant(any(), any()))
                .thenReturn(created);
        when(snapshotMapper.toSnapshot(created)).thenReturn(snapshot);
        when(snapshotCodec.encode(snapshot)).thenReturn("{\"id\":\"pi_created\"}");

        IdempotentCreatePaymentResult result = service.create(command);

        assertThat(result).isEqualTo(new IdempotentCreatePaymentResult(
                snapshot,
                201,
                false
        ));
        ArgumentCaptor<IdempotencyAcquisitionCommand> acquisition = ArgumentCaptor.forClass(
                IdempotencyAcquisitionCommand.class
        );
        verify(acquisitionService).acquire(acquisition.capture());
        assertThat(acquisition.getValue().merchantId()).isEqualTo(41L);
        assertThat(acquisition.getValue().operation())
                .isEqualTo(IdempotencyOperation.PAYMENT_INTENT_CREATE);
        assertThat(acquisition.getValue().idempotencyKey()).isEqualTo(command.idempotencyKey());
        assertThat(acquisition.getValue().requestHash()).matches("[0-9a-f]{64}");
        verify(createPaymentIntentService).createForResolvedMerchant(
                command.toCreateCommand(),
                MERCHANT
        );
        verify(completionService).complete(new IdempotencyCompletionCommand(
                91L,
                "PAYMENT_INTENT",
                "pi_created",
                201,
                "{\"id\":\"pi_created\"}"
        ));
    }

    @Test
    void completedRequestShouldReplayStoredSnapshotWithoutCreatingPayment() {
        IdempotentCreatePaymentCommand command = command("checkout-replay", 50_000L);
        CreatePaymentResponseSnapshot snapshot = snapshot();
        String payload = "{\"id\":\"pi_created\"}";
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.REPLAY,
                null,
                new IdempotencyStoredResponse("PAYMENT_INTENT", "pi_created", 201, payload)
        ));
        when(snapshotCodec.decode(payload)).thenReturn(snapshot);

        IdempotentCreatePaymentResult result = service.create(command);

        assertThat(result).isEqualTo(new IdempotentCreatePaymentResult(snapshot, 201, true));
        verifyNoInteractions(createPaymentIntentService, snapshotMapper, completionService);
    }

    @Test
    void processingRequestShouldFailBeforeCreatingPayment() {
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.IN_PROGRESS,
                null,
                null
        ));

        assertThatThrownBy(() -> service.create(command("checkout-processing", 50_000L)))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        verifyNoInteractions(createPaymentIntentService, snapshotMapper, snapshotCodec,
                completionService);
    }

    @Test
    void reusedKeyShouldFailBeforeCreatingPayment() {
        when(merchantResolver.resolve(PRINCIPAL)).thenReturn(MERCHANT);
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.KEY_REUSED,
                null,
                null
        ));

        assertThatThrownBy(() -> service.create(command("checkout-reused", 50_000L)))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(createPaymentIntentService, snapshotMapper, snapshotCodec,
                completionService);
    }

    @Test
    void invalidSemanticRequestShouldFailBeforeResolvingMerchantOrAcquiringKey() {
        assertThatThrownBy(() -> service.create(command("checkout-invalid", 0L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("amountMinor must be positive");

        verifyNoInteractions(merchantResolver, acquisitionService, createPaymentIntentService,
                snapshotMapper, snapshotCodec, completionService);
    }

    private IdempotentCreatePaymentCommand command(String key, long amountMinor) {
        return new IdempotentCreatePaymentCommand(
                PRINCIPAL,
                IdempotencyKey.of(key),
                amountMinor,
                " vnd ",
                " ORDER-1001 ",
                " Payment for ORDER-1001 "
        );
    }

    private CreatePaymentIntentResult createdResult() {
        return new CreatePaymentIntentResult(
                "pi_created",
                "ORDER-1001",
                50_000L,
                "VND",
                PaymentStatus.CREATED,
                0L,
                0L,
                0L,
                "Payment for ORDER-1001",
                CREATED_AT
        );
    }

    private CreatePaymentResponseSnapshot snapshot() {
        return new CreatePaymentResponseSnapshot(
                "pi_created",
                "ORDER-1001",
                "Payment for ORDER-1001",
                50_000L,
                "VND",
                PaymentStatus.CREATED,
                0L,
                0L,
                CREATED_AT
        );
    }
}
