package com.flowpay.backend.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface PaymentIntentJpaRepository extends JpaRepository<PaymentIntentEntity, Long> {

    Optional<PaymentIntentEntity> findByPublicIdAndMerchantId(String publicId, long merchantId);

    List<PaymentIntentEntity> findAllByMerchantIdOrderByCreatedAtDescIdDesc(long merchantId);
}
