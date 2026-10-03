package com.flowpay.backend.webhook.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface WebhookDeliveryJpaRepository extends JpaRepository<WebhookDeliveryEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from WebhookDeliveryEntity d where d.id = :id")
    Optional<WebhookDeliveryEntity> findByIdForUpdate(@Param("id") long id);

    @Query("""
            select d from WebhookDeliveryEntity d, WebhookEventEntity e, WebhookEndpointEntity p
            where d.webhookEventId = e.id and d.webhookEndpointId = p.id
                and d.publicId = :publicId and e.merchantId = :merchantId and p.merchantId = :merchantId
            """)
    Optional<WebhookDeliveryEntity> findOwned(@Param("publicId") String publicId, @Param("merchantId") long merchantId);

    @Query(value = """
            SELECT * FROM webhook_deliveries WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id LIMIT :limit
            """, nativeQuery = true)
    List<WebhookDeliveryEntity> findDue(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM webhook_deliveries WHERE status = 'DELIVERING' AND lease_expires_at <= :now
            ORDER BY lease_expires_at, id LIMIT :limit
            """, nativeQuery = true)
    List<WebhookDeliveryEntity> findExpiredLeases(@Param("now") Instant now, @Param("limit") int limit);
}
