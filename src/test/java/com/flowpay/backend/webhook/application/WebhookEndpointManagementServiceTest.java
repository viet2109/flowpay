package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.ActiveMerchantSnapshot;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookEndpointManagementServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-13T03:00:00Z");
    private final MerchantAccessApi merchants = mock(MerchantAccessApi.class);
    private final WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
    private final WebhookEndpointPublicIdGenerator ids = mock(WebhookEndpointPublicIdGenerator.class);
    private final WebhookSecretGenerator secrets = mock(WebhookSecretGenerator.class);
    private final WebhookSecretCipher cipher = mock(WebhookSecretCipher.class);
    private final WebhookUrlPolicy policy = mock(WebhookUrlPolicy.class);
    private final WebhookEndpointManagementService service = new WebhookEndpointManagementService(
            merchants, repository, ids, secrets, cipher, policy, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(merchants.requireActiveMerchant("mrc_owner")).thenReturn(new ActiveMerchantSnapshot(7L, "mrc_owner"));
    }

    @Test
    void createsOnlyAfterPolicyAndSubscriptionsAreValidated() {
        when(policy.validate("https://example.com")).thenReturn("https://example.com");
        when(ids.nextId()).thenReturn("wep_created");
        when(secrets.generate()).thenReturn("whsec_raw");
        when(cipher.encrypt("whsec_raw")).thenReturn("encrypted");
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CreatedWebhookEndpoint created = service.create(new CreateWebhookEndpointCommand(
                "mrc_owner", "https://example.com", List.of("refund.succeeded", "payment.failed")));
        assertThat(created.secret()).isEqualTo("whsec_raw");
        assertThat(created.endpoint().events()).containsExactly("payment.failed", "refund.succeeded");
        verify(cipher).encrypt("whsec_raw");
        verify(cipher, never()).decrypt(any());
        verify(repository).save(argThat(endpoint -> endpoint.merchantId() == 7L
                && endpoint.secretCiphertext().equals("encrypted")));
    }

    @Test
    void invalidUrlOrSubscriptionsNeverGenerateSecretOrPersist() {
        when(policy.validate("unsafe")).thenThrow(new IllegalArgumentException("unsafe"));
        assertValidation(() -> service.create(new CreateWebhookEndpointCommand(
                "mrc_owner", "unsafe", List.of("payment.failed"))));
        when(policy.validate("safe")).thenReturn("https://example.com");
        assertValidation(() -> service.create(new CreateWebhookEndpointCommand(
                "mrc_owner", "safe", List.of("payment.failed", "payment.failed"))));
        verifyNoInteractions(secrets, cipher, repository);
    }

    @Test
    void validatesWholePatchBeforeChangingDomainState() {
        WebhookEndpoint endpoint = endpoint();
        when(repository.findByPublicIdAndMerchantId("wep_owned", 7)).thenReturn(Optional.of(endpoint));
        when(policy.validate("safe")).thenReturn("https://new.example");
        assertValidation(() -> service.update(new UpdateWebhookEndpointCommand(
                "mrc_owner", "wep_owned", "safe", List.of("unsupported"))));
        assertThat(endpoint.url()).isEqualTo("https://example.com");
        verify(repository, never()).save(any());
    }

    @Test
    void disabledEndpointRejectsMutationBeforeGeneratingSecret() {
        WebhookEndpoint endpoint = endpoint();
        endpoint.disable(NOW);
        when(repository.findByPublicIdAndMerchantId("wep_owned", 7)).thenReturn(Optional.of(endpoint));
        assertConflict(() -> service.rotateSecret("mrc_owner", "wep_owned"));
        assertConflict(() -> service.update(new UpdateWebhookEndpointCommand(
                "mrc_owner", "wep_owned", "safe", null)));
        verifyNoInteractions(secrets, cipher, policy);
        verify(repository, never()).save(any());
    }

    @Test
    void translatesOptimisticConflictWithoutLeakingPersistenceDetails() {
        when(repository.findByPublicIdAndMerchantIdForUpdate("wep_owned", 7)).thenReturn(Optional.of(endpoint()));
        when(repository.save(any())).thenThrow(new OptimisticLockingFailureException("SQL private details"));
        assertConflict(() -> service.disable("mrc_owner", "wep_owned"));
    }

    @Test
    void allLookupsAreMerchantScoped() {
        when(repository.findByPublicIdAndMerchantId("wep_other", 7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("mrc_owner", "wep_other"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code())
                        .isEqualTo(ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND));
        verify(repository).findByPublicIdAndMerchantId("wep_other", 7);
    }

    @Test
    void secretBearingResultsAndResponsesHaveRedactedToString() {
        WebhookEndpointSummary summary = new WebhookEndpointSummary(
                "wep_test", "https://example.com", endpoint().status(), List.of("payment.failed"), NOW, NOW);
        assertThat(new CreatedWebhookEndpoint(summary, "whsec_sensitive").toString())
                .contains("[REDACTED]").doesNotContain("whsec_sensitive");
        assertThat(new RotatedWebhookSecret("wep_test", "whsec_sensitive", NOW).toString())
                .contains("[REDACTED]").doesNotContain("whsec_sensitive");
    }

    private WebhookEndpoint endpoint() {
        return WebhookEndpoint.create("wep_owned", 7, "https://example.com", "encrypted",
                List.of(WebhookEventType.PAYMENT_FAILED), NOW);
    }

    private void assertValidation(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
    }

    private void assertConflict(ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.code()).isEqualTo(ErrorCode.WEBHOOK_INVALID_STATE);
            assertThat(ex.status().value()).isEqualTo(409);
            assertThat(ex.getMessage()).doesNotContain("SQL", "private details");
        });
    }
}
