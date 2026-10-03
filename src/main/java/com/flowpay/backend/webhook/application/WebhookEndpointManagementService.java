package com.flowpay.backend.webhook.application;

import com.flowpay.backend.common.error.ApiException;
import com.flowpay.backend.common.error.ErrorCode;
import com.flowpay.backend.merchant.application.MerchantAccessApi;
import com.flowpay.backend.webhook.domain.WebhookEndpoint;
import com.flowpay.backend.webhook.domain.WebhookEndpointStatus;
import com.flowpay.backend.webhook.domain.WebhookEventType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WebhookEndpointManagementService implements WebhookEndpointManagementUseCase {
    private final MerchantAccessApi merchantAccess;
    private final WebhookEndpointRepository repository;
    private final WebhookEndpointPublicIdGenerator publicIds;
    private final WebhookSecretGenerator secrets;
    private final WebhookSecretCipher cipher;
    private final WebhookUrlPolicy urlPolicy;
    private final Clock clock;

    @Override
    @Transactional
    public CreatedWebhookEndpoint create(CreateWebhookEndpointCommand command) {
        long merchantId = merchantAccess.requireActiveMerchant(command.merchantPublicId()).internalId();
        String url = validatedUrl(command.url());
        List<WebhookEventType> events = validatedEvents(command.events());
        String secret = secrets.generate();
        WebhookEndpoint saved = save(WebhookEndpoint.create(
                publicIds.nextId(), merchantId, url, cipher.encrypt(secret), events, clock.instant()
        ));
        return new CreatedWebhookEndpoint(summary(saved), secret);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookEndpointSummary> list(String merchantPublicId) {
        long merchantId = merchantAccess.requireActiveMerchant(merchantPublicId).internalId();
        return repository.findAllByMerchantId(merchantId).stream()
                .map(WebhookEndpointManagementService::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WebhookEndpointSummary get(String merchantPublicId, String endpointPublicId) {
        return summary(ownedEndpoint(merchantPublicId, endpointPublicId));
    }

    @Override
    @Transactional
    public WebhookEndpointSummary update(UpdateWebhookEndpointCommand command) {
        WebhookEndpoint endpoint = ownedEndpoint(command.merchantPublicId(), command.endpointPublicId());
        requireActive(endpoint);
        if (command.url() == null && command.events() == null) {
            throw validationError();
        }
        String url = command.url() == null ? null : validatedUrl(command.url());
        List<WebhookEventType> events = command.events() == null ? null : validatedEvents(command.events());
        Instant now = clock.instant();
        if (url != null) {
            endpoint.changeUrl(url, now);
        }
        if (events != null) {
            endpoint.replaceSubscriptions(events, now);
        }
        return summary(save(endpoint));
    }

    @Override
    @Transactional
    public void disable(String merchantPublicId, String endpointPublicId) {
        long merchantId = merchantAccess.requireActiveMerchant(merchantPublicId).internalId();
        WebhookEndpoint endpoint = repository.findByPublicIdAndMerchantIdForUpdate(endpointPublicId, merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND, "The webhook endpoint was not found."));
        if (endpoint.status() == WebhookEndpointStatus.ACTIVE) {
            endpoint.disable(clock.instant());
            save(endpoint);
        }
        // P7-T11: invoke Webhook-owned delivery cancellation here in this same transaction.
    }

    @Override
    @Transactional
    public RotatedWebhookSecret rotateSecret(String merchantPublicId, String endpointPublicId) {
        WebhookEndpoint endpoint = ownedEndpoint(merchantPublicId, endpointPublicId);
        requireActive(endpoint);
        String secret = secrets.generate();
        endpoint.rotateSecret(cipher.encrypt(secret), clock.instant());
        WebhookEndpoint saved = save(endpoint);
        return new RotatedWebhookSecret(saved.publicId(), secret, saved.updatedAt());
    }

    private WebhookEndpoint ownedEndpoint(String merchantPublicId, String endpointPublicId) {
        long merchantId = merchantAccess.requireActiveMerchant(merchantPublicId).internalId();
        return repository.findByPublicIdAndMerchantId(endpointPublicId, merchantId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        ErrorCode.WEBHOOK_ENDPOINT_NOT_FOUND, "The webhook endpoint was not found."));
    }

    private WebhookEndpoint save(WebhookEndpoint endpoint) {
        try {
            return repository.save(endpoint);
        } catch (OptimisticLockingFailureException exception) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.WEBHOOK_INVALID_STATE,
                    "The webhook endpoint changed concurrently. Reload it before retrying.");
        }
    }

    private static void requireActive(WebhookEndpoint endpoint) {
        if (endpoint.status() != WebhookEndpointStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.WEBHOOK_INVALID_STATE,
                    "The webhook endpoint is disabled.");
        }
    }

    private String validatedUrl(String url) {
        try {
            return urlPolicy.validate(url);
        } catch (IllegalArgumentException exception) {
            throw validationError();
        }
    }

    private static List<WebhookEventType> validatedEvents(List<String> events) {
        if (events == null || events.isEmpty() || events.size() > WebhookEventType.values().length
                || new HashSet<>(events).size() != events.size() || events.stream().anyMatch(Objects::isNull)) {
            throw validationError();
        }
        try {
            return events.stream().map(WebhookEventType::fromValue).toList();
        } catch (IllegalArgumentException exception) {
            throw validationError();
        }
    }

    private static ApiException validationError() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "Supply a valid webhook URL and a non-empty set of supported event names.");
    }

    private static WebhookEndpointSummary summary(WebhookEndpoint endpoint) {
        return new WebhookEndpointSummary(endpoint.publicId(), endpoint.url(), endpoint.status(),
                endpoint.subscribedEventTypes().stream().map(WebhookEventType::value).sorted().toList(),
                endpoint.createdAt(), endpoint.updatedAt());
    }
}
