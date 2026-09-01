package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerReferenceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface LedgerTransactionJpaRepository
        extends JpaRepository<LedgerTransactionEntity, Long> {

    @Query("""
            SELECT DISTINCT ledgerTransaction
            FROM LedgerTransactionEntity ledgerTransaction
            LEFT JOIN FETCH ledgerTransaction.entries
            WHERE ledgerTransaction.id = :internalId
            """)
    Optional<LedgerTransactionEntity> findAggregateById(
            @Param("internalId") long internalId
    );

    @Query("""
            SELECT DISTINCT ledgerTransaction
            FROM LedgerTransactionEntity ledgerTransaction
            LEFT JOIN FETCH ledgerTransaction.entries
            WHERE ledgerTransaction.publicId = :publicId
            """)
    Optional<LedgerTransactionEntity> findAggregateByPublicId(
            @Param("publicId") String publicId
    );

    @Query("""
            SELECT DISTINCT ledgerTransaction
            FROM LedgerTransactionEntity ledgerTransaction
            LEFT JOIN FETCH ledgerTransaction.entries
            WHERE ledgerTransaction.postingType = :postingType
              AND ledgerTransaction.referenceType = :referenceType
              AND ledgerTransaction.referenceId = :referenceId
            """)
    Optional<LedgerTransactionEntity> findAggregateByBusinessReference(
            @Param("postingType") LedgerPostingType postingType,
            @Param("referenceType") LedgerReferenceType referenceType,
            @Param("referenceId") String referenceId
    );
}
