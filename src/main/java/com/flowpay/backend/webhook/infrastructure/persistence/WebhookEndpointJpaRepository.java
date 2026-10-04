package com.flowpay.backend.webhook.infrastructure.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

interface WebhookEndpointJpaRepository extends JpaRepository<WebhookEndpointEntity, Long> {
    @EntityGraph(attributePaths = "events")
    Optional<WebhookEndpointEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    @EntityGraph(attributePaths = "events")
    List<WebhookEndpointEntity> findAllByMerchantIdOrderByCreatedAtDescIdDesc(long merchantId);
}
