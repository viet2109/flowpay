package com.flowpay.backend.payment.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.common.security.MerchantApiPrincipal;
import com.flowpay.backend.idempotency.application.ConfirmPaymentFingerprint;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionDecision;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionResult;
import com.flowpay.backend.idempotency.application.IdempotencyAcquisitionService;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionCommand;
import com.flowpay.backend.idempotency.application.IdempotencyCompletionService;
import com.flowpay.backend.idempotency.application.IdempotencyKeyReusedException;
import com.flowpay.backend.idempotency.application.IdempotencyRequestInProgressException;
import com.flowpay.backend.idempotency.application.IdempotencyReservationReleaseCommand;
import com.flowpay.backend.idempotency.application.IdempotencyReservationReleaseService;
import com.flowpay.backend.idempotency.application.IdempotencyStoredResponse;
import com.flowpay.backend.idempotency.application.RequestFingerprintService;
import com.flowpay.backend.idempotency.domain.IdempotencyKey;
import com.flowpay.backend.idempotency.domain.IdempotencyOperation;
import com.flowpay.backend.payment.domain.PaymentStatus;
import com.flowpay.backend.payment.domain.PaymentTransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentConfirmPaymentServiceTest {

    private static final String REQUEST_HASH = "a".repeat(64);
    private static final MerchantApiPrincipal PRINCIPAL = new MerchantApiPrincipal(
            "mrc_confirm_idempotent",
            "key_confirm_idempotent"
    );
    private static final IdempotentConfirmPaymentCommand COMMAND =
            new IdempotentConfirmPaymentCommand(
                    PRINCIPAL,
                    IdempotencyKey.of("confirm-order-1001"),
                    "pi_confirm_idempotent"
            );
    private static final PaymentConfirmationPreflight PREFLIGHT =
            new PaymentConfirmationPreflight(
                    41L,
                    "pi_confirm_idempotent",
                    PaymentStatus.CREATED
            );
    private static final PreparedPaymentConfirmation PREPARED =
            new PreparedPaymentConfirmation(
                    "pi_confirm_idempotent",
                    "ptxn_confirm_idempotent",
                    50_000L,
                    "VND",
                    "SIMULATOR"
            );
    private static final FinalizedPaymentConfirmation FINALIZED =
            new FinalizedPaymentConfirmation(
                    "pi_confirm_idempotent",
                    PaymentStatus.SUCCEEDED,
                    "ptxn_confirm_idempotent",
                    PaymentTransactionStatus.SUCCEEDED,
                    "SIMULATOR",
                    "sim_confirm_idempotent",
                    null,
                    null
            );
    private static final ConfirmPaymentResponseSnapshot SNAPSHOT =
            new ConfirmPaymentResponseSnapshot(
                    "pi_confirm_idempotent",
                    PaymentStatus.SUCCEEDED,
                    "ptxn_confirm_idempotent",
                    PaymentTransactionStatus.SUCCEEDED,
                    "SIMULATOR",
                    "sim_confirm_idempotent",
                    null,
                    null
            );
    private static final String SNAPSHOT_JSON = """
            {"paymentId":"pi_confirm_idempotent","paymentStatus":"SUCCEEDED",\
            "transactionId":"ptxn_confirm_idempotent","transactionStatus":"SUCCEEDED",\
            "provider":"SIMULATOR","providerTransactionId":"sim_confirm_idempotent",\
            "failureCode":null,"failureMessage":null}
            """;

    @Mock
    private RequestFingerprintService fingerprintService;

    @Mock
    private PaymentConfirmationPreflightService preflightService;

    @Mock
    private IdempotencyAcquisitionService acquisitionService;

    @Mock
    private IdempotencyReservationReleaseService releaseService;

    @Mock
    private ConfirmPaymentService confirmationService;

    @Mock
    private ConfirmPaymentResponseSnapshotMapper snapshotMapper;

    @Mock
    private ConfirmPaymentResponseSnapshotCodec snapshotCodec;

    @Mock
    private IdempotencyCompletionService completionService;

    private IdempotentConfirmPaymentService service;

    @BeforeEach
    void setUp() {
        service = new IdempotentConfirmPaymentService(
                fingerprintService,
                preflightService,
                acquisitionService,
                releaseService,
                confirmationService,
                snapshotMapper,
                snapshotCodec,
                completionService
        );
    }

    @Test
    void newRequestShouldReserveResourceBeforeExecutingConfirmation() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                71L,
                null
        ));
        when(confirmationService.prepare(COMMAND.toConfirmCommand())).thenReturn(PREPARED);
        when(confirmationService.executePrepared(PREPARED)).thenReturn(FINALIZED);
        when(snapshotMapper.toSnapshot(FINALIZED)).thenReturn(SNAPSHOT);
        when(snapshotCodec.encode(SNAPSHOT)).thenReturn(SNAPSHOT_JSON);

        IdempotentConfirmPaymentResult result = service.confirm(COMMAND);

        assertThat(result).isEqualTo(new IdempotentConfirmPaymentResult(
                SNAPSHOT,
                200,
                false
        ));
        ArgumentCaptor<IdempotencyAcquisitionCommand> acquisition =
                ArgumentCaptor.forClass(IdempotencyAcquisitionCommand.class);
        verify(acquisitionService).acquire(acquisition.capture());
        assertThat(acquisition.getValue().merchantId()).isEqualTo(41L);
        assertThat(acquisition.getValue().operation())
                .isEqualTo(IdempotencyOperation.PAYMENT_INTENT_CONFIRM);
        assertThat(acquisition.getValue().idempotencyKey())
                .isEqualTo(COMMAND.idempotencyKey());
        assertThat(acquisition.getValue().requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(acquisition.getValue().resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(acquisition.getValue().resourcePublicId())
                .isEqualTo("pi_confirm_idempotent");
        verify(confirmationService).prepare(COMMAND.toConfirmCommand());
        verify(confirmationService).executePrepared(PREPARED);
        verify(completionService).complete(new IdempotencyCompletionCommand(
                71L,
                "PAYMENT_INTENT",
                "pi_confirm_idempotent",
                200,
                SNAPSHOT_JSON
        ));
        verifyNoInteractions(releaseService);
    }

    @Test
    void completedRequestShouldReturnReplayWithoutExecutingConfirmation() {
        when(fingerprintService.fingerprint(any(ConfirmPaymentFingerprint.class)))
                .thenReturn(REQUEST_HASH);
        when(preflightService.preflight(COMMAND.toConfirmCommand())).thenReturn(
                new PaymentConfirmationPreflight(
                        41L,
                        "pi_confirm_idempotent",
                        PaymentStatus.SUCCEEDED
                )
        );
        IdempotencyStoredResponse stored = new IdempotencyStoredResponse(
                "PAYMENT_INTENT",
                "pi_confirm_idempotent",
                200,
                SNAPSHOT_JSON
        );
        when(acquisitionService.findExisting(any())).thenReturn(Optional.of(
                new IdempotencyAcquisitionResult(
                        IdempotencyAcquisitionDecision.REPLAY,
                        null,
                        stored
                )
        ));
        when(snapshotCodec.decode(SNAPSHOT_JSON)).thenReturn(SNAPSHOT);

        IdempotentConfirmPaymentResult result = service.confirm(COMMAND);

        assertThat(result).isEqualTo(new IdempotentConfirmPaymentResult(
                SNAPSHOT,
                200,
                true
        ));
        verifyNoInteractions(
                confirmationService,
                releaseService,
                snapshotMapper,
                completionService
        );
    }

    @Test
    void processingRequestShouldFailBeforeExecutingConfirmation() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.IN_PROGRESS,
                null,
                null
        ));

        assertThatThrownBy(() -> service.confirm(COMMAND))
                .isInstanceOf(IdempotencyRequestInProgressException.class);

        verifyNoInteractions(confirmationService, releaseService);
    }

    @Test
    void reusedKeyShouldFailBeforeExecutingConfirmation() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.KEY_REUSED,
                null,
                null
        ));

        assertThatThrownBy(() -> service.confirm(COMMAND))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verifyNoInteractions(confirmationService, releaseService);
    }

    @Test
    void tx1FailureShouldReleaseOnlyTheOwnedProcessingReservation() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                73L,
                null
        ));
        ApiException failure = new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.PAYMENT_INVALID_STATE,
                "The payment intent cannot be confirmed from its current state."
        );
        when(confirmationService.prepare(COMMAND.toConfirmCommand())).thenThrow(failure);

        assertThatThrownBy(() -> service.confirm(COMMAND)).isSameAs(failure);

        ArgumentCaptor<IdempotencyReservationReleaseCommand> release =
                ArgumentCaptor.forClass(IdempotencyReservationReleaseCommand.class);
        verify(releaseService).release(release.capture());
        assertThat(release.getValue().executionId()).isEqualTo(73L);
        assertThat(release.getValue().merchantId()).isEqualTo(41L);
        assertThat(release.getValue().operation())
                .isEqualTo(IdempotencyOperation.PAYMENT_INTENT_CONFIRM);
        assertThat(release.getValue().idempotencyKey()).isEqualTo(COMMAND.idempotencyKey());
        assertThat(release.getValue().requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(release.getValue().resourceType()).isEqualTo("PAYMENT_INTENT");
        assertThat(release.getValue().resourcePublicId())
                .isEqualTo("pi_confirm_idempotent");
        verify(confirmationService, never()).executePrepared(any());
    }

    @Test
    void failureAfterTx1ShouldRetainReservationBecauseProviderOutcomeMayBeUncertain() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                74L,
                null
        ));
        when(confirmationService.prepare(COMMAND.toConfirmCommand())).thenReturn(PREPARED);
        RuntimeException uncertain = new RuntimeException("provider connection closed");
        when(confirmationService.executePrepared(PREPARED)).thenThrow(uncertain);

        assertThatThrownBy(() -> service.confirm(COMMAND)).isSameAs(uncertain);

        verifyNoInteractions(releaseService);
    }

    @Test
    void completionFailureAfterProviderShouldRetainReservationForSafeRecovery() {
        stubPreflight();
        when(acquisitionService.acquire(any())).thenReturn(new IdempotencyAcquisitionResult(
                IdempotencyAcquisitionDecision.NEW,
                75L,
                null
        ));
        when(confirmationService.prepare(COMMAND.toConfirmCommand())).thenReturn(PREPARED);
        when(confirmationService.executePrepared(PREPARED)).thenReturn(FINALIZED);
        when(snapshotMapper.toSnapshot(FINALIZED)).thenReturn(SNAPSHOT);
        when(snapshotCodec.encode(SNAPSHOT)).thenReturn(SNAPSHOT_JSON);
        RuntimeException completionFailure = new RuntimeException("completion unavailable");
        org.mockito.Mockito.doThrow(completionFailure)
                .when(completionService)
                .complete(any());

        assertThatThrownBy(() -> service.confirm(COMMAND)).isSameAs(completionFailure);

        verifyNoInteractions(releaseService);
    }

    @Test
    void invalidPreflightShouldCreateNoReservation() {
        when(fingerprintService.fingerprint(any(ConfirmPaymentFingerprint.class)))
                .thenReturn(REQUEST_HASH);
        ApiException invalidState = new ApiException(
                HttpStatus.CONFLICT,
                ErrorCode.PAYMENT_INVALID_STATE,
                "The payment intent cannot be confirmed from its current state."
        );
        when(preflightService.preflight(COMMAND.toConfirmCommand())).thenThrow(invalidState);

        assertThatThrownBy(() -> service.confirm(COMMAND)).isSameAs(invalidState);

        verifyNoInteractions(acquisitionService, confirmationService, releaseService);
    }

    @Test
    void invalidStateWithoutExistingReservationShouldCreateNoReservation() {
        when(fingerprintService.fingerprint(any(ConfirmPaymentFingerprint.class)))
                .thenReturn(REQUEST_HASH);
        when(preflightService.preflight(COMMAND.toConfirmCommand())).thenReturn(
                new PaymentConfirmationPreflight(
                        41L,
                        "pi_confirm_idempotent",
                        PaymentStatus.PROCESSING
                )
        );
        when(acquisitionService.findExisting(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(COMMAND))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ErrorCode.PAYMENT_INVALID_STATE));

        verify(acquisitionService, never()).acquire(any());
        verifyNoInteractions(confirmationService, releaseService);
    }

    @Test
    void orchestrationMustNotOpenAnOuterDatabaseTransaction() throws NoSuchMethodException {
        assertThat(IdempotentConfirmPaymentService.class.getAnnotation(Transactional.class))
                .isNull();
        assertThat(IdempotentConfirmPaymentService.class
                .getDeclaredMethod("confirm", IdempotentConfirmPaymentCommand.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(Arrays.stream(IdempotentConfirmPaymentService.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(type -> type.contains("TransactionTemplate")
                        || type.contains("TransactionManager"));
    }

    private void stubPreflight() {
        when(fingerprintService.fingerprint(any(ConfirmPaymentFingerprint.class)))
                .thenReturn(REQUEST_HASH);
        when(preflightService.preflight(COMMAND.toConfirmCommand())).thenReturn(PREFLIGHT);
    }
}
