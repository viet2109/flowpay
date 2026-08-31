package com.flowpay.backend.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

interface PaymentIntentJpaRepository extends
        JpaRepository<PaymentIntentEntity, Long>,
        JpaSpecificationExecutor<PaymentIntentEntity> {

    Optional<PaymentIntentEntity> findByPublicId(String publicId);

    Optional<PaymentIntentEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT payment
            FROM PaymentIntentEntity payment
            WHERE payment.publicId = :publicId
              AND payment.merchantId = :merchantId
            """)
    Optional<PaymentIntentEntity> findByPublicIdAndMerchantIdForUpdate(
            @Param("publicId") String publicId,
            @Param("merchantId") long merchantId
    );

    List<PaymentIntentEntity> findAllByMerchantIdOrderByCreatedAtDescIdDesc(long merchantId);
}
