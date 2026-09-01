package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.application.LedgerTransactionRepository;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class LedgerTransactionRepositoryAdapter implements LedgerTransactionRepository {

    private static final String INSERT_TRANSACTION_IF_ABSENT_SQL = """
            INSERT INTO ledger_transactions (
                public_id,
                posting_type,
                reference_type,
                reference_id,
                currency,
                description,
                occurred_at,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (posting_type, reference_type, reference_id) DO NOTHING
            RETURNING id
            """;

    private static final String INSERT_ENTRY_SQL = """
            INSERT INTO ledger_entries (
                ledger_transaction_id,
                ledger_account_id,
                entry_no,
                direction,
                amount_minor,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final LedgerTransactionJpaRepository transactionRepository;
    private final LedgerAccountJpaRepository accountRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<LedgerTransaction> tryInsert(LedgerTransaction transaction) {
        if (transaction.internalId() != null) {
            throw new IllegalArgumentException("only a new ledger transaction can be inserted");
        }
        Optional<Long> internalId = jdbcTemplate.query(
                INSERT_TRANSACTION_IF_ABSENT_SQL,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                transaction.publicId(),
                transaction.postingType().name(),
                transaction.businessReference().type().name(),
                transaction.businessReference().referenceId(),
                transaction.currency().getCurrencyCode(),
                transaction.description(),
                transaction.occurredAt().atOffset(ZoneOffset.UTC),
                transaction.createdAt().atOffset(ZoneOffset.UTC)
        ).stream().findFirst();

        if (internalId.isEmpty()) {
            return Optional.empty();
        }
        long savedInternalId = internalId.orElseThrow();
        transaction.entries().forEach(entry -> insertEntry(savedInternalId, entry));
        return findByInternalId(savedInternalId);
    }

    @Override
    public Optional<LedgerTransaction> findByPublicId(String publicId) {
        return transactionRepository.findAggregateByPublicId(publicId)
                .map(this::toDomain);
    }

    @Override
    public Optional<LedgerTransaction> findByBusinessReference(
            LedgerPostingType postingType,
            LedgerBusinessReference businessReference
    ) {
        if (postingType == null) {
            throw new IllegalArgumentException("postingType must not be null");
        }
        if (businessReference == null) {
            throw new IllegalArgumentException("businessReference must not be null");
        }
        return transactionRepository.findAggregateByBusinessReference(
                postingType,
                businessReference.type(),
                businessReference.referenceId()
        ).map(this::toDomain);
    }

    private Optional<LedgerTransaction> findByInternalId(long internalId) {
        return transactionRepository.findAggregateById(internalId).map(this::toDomain);
    }

    private void insertEntry(long transactionId, LedgerEntry entry) {
        jdbcTemplate.update(
                INSERT_ENTRY_SQL,
                transactionId,
                entry.ledgerAccountId(),
                entry.entryNo(),
                entry.direction().name(),
                entry.amountMinor(),
                entry.createdAt().atOffset(ZoneOffset.UTC)
        );
    }

    private LedgerTransaction toDomain(LedgerTransactionEntity transaction) {
        Map<Long, Currency> accountCurrencies = accountRepository.findAllById(
                transaction.entries().stream()
                        .map(LedgerEntryEntity::ledgerAccountId)
                        .distinct()
                        .toList()
        ).stream().collect(Collectors.toMap(
                LedgerAccountEntity::id,
                account -> Money.of(0L, account.currency()).currency(),
                (first, second) -> first
        ));
        return LedgerTransactionPersistenceMapper.toDomain(
                transaction,
                accountCurrencies
        );
    }
}
