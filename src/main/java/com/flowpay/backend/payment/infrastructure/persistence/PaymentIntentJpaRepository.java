package com.flowpay.backend.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

interface PaymentIntentJpaRepository extends
        JpaRepository<PaymentIntentEntity, Long>,
        JpaSpecificationExecutor<PaymentIntentEntity> {

    Optional<PaymentIntentEntity> findByPublicId(String publicId);

    Optional<PaymentIntentEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    List<PaymentIntentEntity> findAllByMerchantIdOrderByCreatedAtDescIdDesc(long merchantId);
}
