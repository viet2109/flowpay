package com.flowpay.backend.infrastructure.messaging.outbox.persistence;

import com.flowpay.backend.infrastructure.messaging.outbox.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    List<OutboxEventEntity> findByStatusInAndAvailableAtLessThanEqual(
            Collection<OutboxStatus> statuses,
            Instant availableAt,
            Pageable pageable
    );
}
