package com.flowpay.backend.refund.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface RefundJpaRepository extends JpaRepository<RefundEntity, Long> {

    Optional<RefundEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT refund
            FROM RefundEntity refund
            WHERE refund.publicId = :publicId
              AND refund.merchantId = :merchantId
            """)
    Optional<RefundEntity> findByPublicIdAndMerchantIdForUpdate(
            @Param("publicId") String publicId,
            @Param("merchantId") long merchantId
    );

    Page<RefundEntity> findByPaymentIntentIdAndMerchantId(
            long paymentIntentId,
            long merchantId,
            Pageable pageable
    );
}
