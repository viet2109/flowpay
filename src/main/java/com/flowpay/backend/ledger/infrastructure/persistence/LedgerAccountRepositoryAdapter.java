package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.ledger.application.LedgerAccountRepository;
import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class LedgerAccountRepositoryAdapter implements LedgerAccountRepository {

    private static final String INSERT_IF_ABSENT_SQL = """
            INSERT INTO ledger_accounts (
                public_id,
                account_code,
                account_type,
                owner_type,
                owner_id,
                currency,
                status,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (account_code) DO NOTHING
            RETURNING id
            """;

    private final LedgerAccountJpaRepository repository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public LedgerAccount save(LedgerAccount account) {
        LedgerAccountEntity saved = repository.saveAndFlush(
                LedgerAccountPersistenceMapper.toEntity(account)
        );
        return LedgerAccountPersistenceMapper.toDomain(saved);
    }

    @Override
    public Optional<LedgerAccount> tryInsert(LedgerAccount account) {
        if (account.internalId() != null) {
            throw new IllegalArgumentException("only a new ledger account can be inserted");
        }
        return jdbcTemplate.query(
                INSERT_IF_ABSENT_SQL,
                (resultSet, rowNumber) -> resultSet.getLong("id"),
                account.publicId(),
                account.accountCode().value(),
                account.accountType().name(),
                account.ownerType().name(),
                account.ownerId(),
                account.currency().getCurrencyCode(),
                account.status().name(),
                account.createdAt().atOffset(ZoneOffset.UTC)
        ).stream().findFirst().map(
                internalId -> LedgerAccountPersistenceMapper.withInternalId(account, internalId)
        );
    }

    @Override
    public Optional<LedgerAccount> findByAccountCode(LedgerAccountCode accountCode) {
        if (accountCode == null) {
            throw new IllegalArgumentException("accountCode must not be null");
        }
        return repository.findByAccountCode(accountCode.value())
                .map(LedgerAccountPersistenceMapper::toDomain);
    }
}
