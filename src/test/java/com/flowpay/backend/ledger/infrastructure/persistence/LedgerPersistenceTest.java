package com.flowpay.backend.ledger.infrastructure.persistence;

import com.flowpay.backend.common.money.Money;
import com.flowpay.backend.ledger.application.LedgerAccountRepository;
import com.flowpay.backend.ledger.application.LedgerTransactionRepository;
import com.flowpay.backend.ledger.domain.LedgerAccount;
import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import com.flowpay.backend.ledger.domain.LedgerAccountStatus;
import com.flowpay.backend.ledger.domain.LedgerAccountType;
import com.flowpay.backend.ledger.domain.LedgerBusinessReference;
import com.flowpay.backend.ledger.domain.LedgerEntry;
import com.flowpay.backend.ledger.domain.LedgerEntryDirection;
import com.flowpay.backend.ledger.domain.LedgerEntryDraft;
import com.flowpay.backend.ledger.domain.LedgerOwnerType;
import com.flowpay.backend.ledger.domain.LedgerPostingType;
import com.flowpay.backend.ledger.domain.LedgerTransaction;
import com.flowpay.backend.testing.PostgresIntegrationTest;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LedgerPersistenceTest extends PostgresIntegrationTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant CREATED_AT = OCCURRED_AT.plusSeconds(1);

    @Autowired
    private LedgerAccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanLedgerData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE ledger_entries, ledger_transactions, ledger_accounts
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void shouldSaveAndReloadLedgerAccounts() {
        LedgerAccount system = accountRepository.save(
                LedgerAccount.createSystemClearing("la_system_vnd", VND, CREATED_AT)
        );
        LedgerAccount merchant = accountRepository.save(
                LedgerAccount.createMerchantPayable(
                        "la_merchant_15_vnd",
                        15L,
                        VND,
                        CREATED_AT.plusSeconds(1)
                )
        );

        LedgerAccount reloadedSystem = accountRepository.findByAccountCode(
                LedgerAccountCode.systemClearing(VND)
        ).orElseThrow();
        LedgerAccount reloadedMerchant = accountRepository.findByAccountCode(
                LedgerAccountCode.merchantPayable(15L, VND)
        ).orElseThrow();

        assertThat(system.internalId()).isPositive();
        assertThat(merchant.internalId()).isPositive();
        assertThat(reloadedSystem.publicId()).isEqualTo("la_system_vnd");
        assertThat(reloadedSystem.accountType()).isEqualTo(LedgerAccountType.SYSTEM_CLEARING);
        assertThat(reloadedSystem.ownerType()).isEqualTo(LedgerOwnerType.SYSTEM);
        assertThat(reloadedSystem.ownerId()).isNull();
        assertThat(reloadedSystem.currency()).isEqualTo(VND);
        assertThat(reloadedSystem.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(reloadedSystem.createdAt()).isEqualTo(CREATED_AT);
        assertThat(reloadedMerchant.publicId()).isEqualTo("la_merchant_15_vnd");
        assertThat(reloadedMerchant.accountType()).isEqualTo(LedgerAccountType.MERCHANT_PAYABLE);
        assertThat(reloadedMerchant.ownerType()).isEqualTo(LedgerOwnerType.MERCHANT);
        assertThat(reloadedMerchant.ownerId()).isEqualTo(15L);
        assertThat(reloadedMerchant.currency()).isEqualTo(VND);
    }

    @Test
    void shouldInsertAccountOnlyWhenItsDeterministicCodeIsAbsent() {
        LedgerAccount first = LedgerAccount.createSystemClearing(
                "la_first_system_vnd",
                VND,
                CREATED_AT
        );
        LedgerAccount competing = LedgerAccount.createSystemClearing(
                "la_competing_system_vnd",
                VND,
                CREATED_AT.plusSeconds(1)
        );

        Optional<LedgerAccount> inserted = accountRepository.tryInsert(first);
        Optional<LedgerAccount> duplicate = accountRepository.tryInsert(competing);

        assertThat(inserted).isPresent();
        assertThat(duplicate).isEmpty();
        assertThat(accountRepository.findByAccountCode(
                LedgerAccountCode.systemClearing(VND)
        )).get().extracting(LedgerAccount::publicId).isEqualTo("la_first_system_vnd");
        assertThat(countRows("ledger_accounts")).isEqualTo(1L);
    }

    @Test
    void shouldPersistAndReloadPaymentStyleTransactionWithOrderedEntries() {
        LedgerAccount system = saveSystem("payment", VND);
        LedgerAccount merchant = saveMerchant("payment", 15L, VND);
        LedgerTransaction candidate = paymentTransaction(
                "ltxn_payment",
                "pi_payment",
                system,
                merchant,
                1_000_000L,
                VND
        );

        LedgerTransaction saved = insert(candidate).orElseThrow();
        LedgerTransaction byPublicId = transactionRepository.findByPublicId(
                "ltxn_payment"
        ).orElseThrow();
        LedgerTransaction byReference = transactionRepository.findByBusinessReference(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_payment")
        ).orElseThrow();

        assertThat(saved.internalId()).isPositive();
        assertEquivalentTransaction(byPublicId, saved);
        assertEquivalentTransaction(byReference, saved);
        assertThat(saved.currency()).isEqualTo(VND);
        assertThat(saved.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(saved.createdAt()).isEqualTo(CREATED_AT);
        assertThat(saved.entries()).extracting(LedgerEntry::entryNo).containsExactly(1, 2);
        assertThat(saved.entries()).extracting(LedgerEntry::ledgerAccountId)
                .containsExactly(system.internalId(), merchant.internalId());
        assertThat(saved.entries()).extracting(LedgerEntry::direction)
                .containsExactly(LedgerEntryDirection.DEBIT, LedgerEntryDirection.CREDIT);
        assertThat(saved.entries()).extracting(LedgerEntry::amountMinor)
                .containsExactly(1_000_000L, 1_000_000L);
        assertThat(saved.entries()).allSatisfy(entry -> {
            assertThat(entry.internalId()).isPositive();
            assertThat(entry.ledgerTransactionId()).isEqualTo(saved.internalId());
            assertThat(entry.accountCurrency()).isEqualTo(VND);
            assertThat(entry.createdAt()).isEqualTo(CREATED_AT);
        });
    }

    @Test
    void shouldPersistAndReloadRefundStyleTransaction() {
        LedgerAccount system = saveSystem("refund", USD);
        LedgerAccount merchant = saveMerchant("refund", 29L, USD);
        LedgerTransaction candidate = LedgerTransaction.post(
                "ltxn_refund",
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund("re_refund"),
                USD,
                "Refund succeeded",
                OCCURRED_AT,
                CREATED_AT,
                List.of(
                        LedgerEntryDraft.debit(merchant, new Money(300_00L, USD)),
                        LedgerEntryDraft.credit(system, new Money(300_00L, USD))
                )
        );

        LedgerTransaction saved = insert(candidate).orElseThrow();
        LedgerTransaction reloaded = transactionRepository.findByBusinessReference(
                LedgerPostingType.REFUND_SUCCEEDED,
                LedgerBusinessReference.refund("re_refund")
        ).orElseThrow();

        assertEquivalentTransaction(reloaded, saved);
        assertThat(reloaded.currency()).isEqualTo(USD);
        assertThat(reloaded.entries()).extracting(LedgerEntry::ledgerAccountId)
                .containsExactly(merchant.internalId(), system.internalId());
        assertThat(reloaded.entries()).extracting(LedgerEntry::direction)
                .containsExactly(LedgerEntryDirection.DEBIT, LedgerEntryDirection.CREDIT);
    }

    @Test
    void shouldReturnEmptyWithoutAddingEntriesForDuplicateBusinessReference() {
        LedgerAccount system = saveSystem("duplicate", VND);
        LedgerAccount merchant = saveMerchant("duplicate", 15L, VND);
        LedgerTransaction first = paymentTransaction(
                "ltxn_duplicate_first",
                "pi_duplicate",
                system,
                merchant,
                1_000L,
                VND
        );
        LedgerTransaction duplicate = paymentTransaction(
                "ltxn_duplicate_second",
                "pi_duplicate",
                system,
                merchant,
                1_000L,
                VND
        );

        assertThat(insert(first)).isPresent();
        assertThat(insert(duplicate)).isEmpty();

        assertThat(countRows("ledger_transactions")).isEqualTo(1L);
        assertThat(countRows("ledger_entries")).isEqualTo(2L);
        assertThat(transactionRepository.findByBusinessReference(
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent("pi_duplicate")
        )).get().extracting(LedgerTransaction::publicId)
                .isEqualTo("ltxn_duplicate_first");
    }

    @Test
    void shouldRollbackHeaderAndEntriesTogetherWhenAnEntryCannotBeInserted() {
        LedgerAccount missingSystem = LedgerAccount.rehydrate(
                9_001L,
                "la_missing_system",
                "SYSTEM_CLEARING:VND",
                LedgerAccountType.SYSTEM_CLEARING,
                LedgerOwnerType.SYSTEM,
                null,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        );
        LedgerAccount missingMerchant = LedgerAccount.rehydrate(
                9_002L,
                "la_missing_merchant",
                "MERCHANT_PAYABLE:15:VND",
                LedgerAccountType.MERCHANT_PAYABLE,
                LedgerOwnerType.MERCHANT,
                15L,
                VND,
                LedgerAccountStatus.ACTIVE,
                CREATED_AT
        );
        LedgerTransaction candidate = paymentTransaction(
                "ltxn_rollback",
                "pi_rollback",
                missingSystem,
                missingMerchant,
                1_000L,
                VND
        );

        assertThatThrownBy(() -> insert(candidate))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(countRows("ledger_transactions")).isZero();
        assertThat(countRows("ledger_entries")).isZero();
    }

    @Test
    void persistenceSurfaceShouldRemainModuleOwnedAndAppendOnly() throws Exception {
        assertPortDoesNotLeakPersistenceTypes(LedgerAccountRepository.class);
        assertPortDoesNotLeakPersistenceTypes(LedgerTransactionRepository.class);
        assertThat(Arrays.stream(LedgerTransactionRepository.class.getDeclaredMethods())
                .map(Method::getName))
                .noneMatch(name -> name.startsWith("save"))
                .noneMatch(name -> name.startsWith("delete"))
                .noneMatch(name -> name.startsWith("remove"))
                .noneMatch(name -> name.startsWith("update"));
        assertThat(Modifier.isPublic(LedgerAccountEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerTransactionEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerEntryEntity.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerAccountJpaRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerTransactionJpaRepository.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerAccountPersistenceMapper.class.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(LedgerTransactionPersistenceMapper.class.getModifiers()))
                .isFalse();
        assertThat(Arrays.stream(LedgerTransactionEntity.class.getDeclaredFields()))
                .noneMatch(field -> field.isAnnotationPresent(Version.class));
        assertThat(Arrays.stream(LedgerEntryEntity.class.getDeclaredFields()))
                .noneMatch(field -> field.isAnnotationPresent(Version.class));

        OneToMany entriesMapping = LedgerTransactionEntity.class
                .getDeclaredField("entries")
                .getAnnotation(OneToMany.class);
        assertThat(entriesMapping.orphanRemoval()).isFalse();
        assertThat(entriesMapping.cascade())
                .noneMatch(cascade -> cascade.name().equals("REMOVE"));
    }

    private Optional<LedgerTransaction> insert(LedgerTransaction transaction) {
        return new TransactionTemplate(transactionManager).execute(
                status -> transactionRepository.tryInsert(transaction)
        );
    }

    private LedgerAccount saveSystem(String suffix, Currency currency) {
        return accountRepository.save(LedgerAccount.createSystemClearing(
                "la_system_" + suffix,
                currency,
                CREATED_AT
        ));
    }

    private LedgerAccount saveMerchant(String suffix, long merchantId, Currency currency) {
        return accountRepository.save(LedgerAccount.createMerchantPayable(
                "la_merchant_" + suffix,
                merchantId,
                currency,
                CREATED_AT
        ));
    }

    private static LedgerTransaction paymentTransaction(
            String publicId,
            String paymentPublicId,
            LedgerAccount system,
            LedgerAccount merchant,
            long amountMinor,
            Currency currency
    ) {
        Money amount = new Money(amountMinor, currency);
        return LedgerTransaction.post(
                publicId,
                LedgerPostingType.PAYMENT_SUCCEEDED,
                LedgerBusinessReference.paymentIntent(paymentPublicId),
                currency,
                "Payment succeeded",
                OCCURRED_AT,
                CREATED_AT,
                List.of(
                        LedgerEntryDraft.debit(system, amount),
                        LedgerEntryDraft.credit(merchant, amount)
                )
        );
    }

    private static void assertEquivalentTransaction(
            LedgerTransaction actual,
            LedgerTransaction expected
    ) {
        assertThat(actual.internalId()).isEqualTo(expected.internalId());
        assertThat(actual.publicId()).isEqualTo(expected.publicId());
        assertThat(actual.postingType()).isEqualTo(expected.postingType());
        assertThat(actual.businessReference()).isEqualTo(expected.businessReference());
        assertThat(actual.currency()).isEqualTo(expected.currency());
        assertThat(actual.description()).isEqualTo(expected.description());
        assertThat(actual.occurredAt()).isEqualTo(expected.occurredAt());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
        assertThat(actual.entries()).extracting(
                LedgerEntry::ledgerAccountId,
                LedgerEntry::entryNo,
                LedgerEntry::direction,
                LedgerEntry::amountMinor,
                LedgerEntry::createdAt
        ).containsExactlyElementsOf(expected.entries().stream()
                .map(entry -> org.assertj.core.groups.Tuple.tuple(
                        entry.ledgerAccountId(),
                        entry.entryNo(),
                        entry.direction(),
                        entry.amountMinor(),
                        entry.createdAt()
                ))
                .toList());
    }

    private long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0L : count;
    }

    private static void assertPortDoesNotLeakPersistenceTypes(Class<?> repositoryPort) {
        assertThat(Arrays.stream(repositoryPort.getDeclaredMethods())
                .map(Method::toGenericString))
                .noneMatch(signature -> signature.contains(".infrastructure.persistence."))
                .noneMatch(signature -> signature.contains("org.springframework.data."))
                .noneMatch(signature -> signature.contains("jakarta.persistence."));
    }
}
