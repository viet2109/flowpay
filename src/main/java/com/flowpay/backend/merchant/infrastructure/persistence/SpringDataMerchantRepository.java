package com.flowpay.backend.merchant.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataMerchantRepository extends JpaRepository<MerchantEntity, Long> {

    Optional<MerchantEntity> findByPublicId(String publicId);
}
