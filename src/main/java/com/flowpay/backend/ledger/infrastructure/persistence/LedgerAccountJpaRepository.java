package com.flowpay.backend.ledger.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface LedgerAccountJpaRepository extends JpaRepository<LedgerAccountEntity, Long> {

    Optional<LedgerAccountEntity> findByAccountCode(String accountCode);
}
