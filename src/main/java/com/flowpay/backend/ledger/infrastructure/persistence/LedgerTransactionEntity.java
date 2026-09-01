package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerReferenceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity(name = "LedgerTransactionEntity")
@Table(name = "ledger_transactions")
@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class LedgerTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "public_id",
            nullable = false,
            unique = true,
            length = 64,
            updatable = false
    )
    private String publicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "posting_type", nullable = false, length = 32, updatable = false)
    private LedgerPostingType postingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 32, updatable = false)
    private LedgerReferenceType referenceType;

    @Column(name = "reference_id", nullable = false, length = 64, updatable = false)
    private String referenceId;

    @Column(
            nullable = false,
            length = 3,
            columnDefinition = "CHAR(3)",
            updatable = false
    )
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false)
    private String description;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "ledgerTransaction")
    @OrderBy("entryNo ASC")
    private List<LedgerEntryEntity> entries = new ArrayList<>();
}
