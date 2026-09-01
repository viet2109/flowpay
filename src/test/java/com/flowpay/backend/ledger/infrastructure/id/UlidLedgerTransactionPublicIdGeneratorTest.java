package com.flowpay.backend.ledger.infrastructure.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UlidLedgerTransactionPublicIdGeneratorTest {

    @Test
    void shouldGenerateUniquePrefixedIds() {
        UlidLedgerTransactionPublicIdGenerator generator =
                new UlidLedgerTransactionPublicIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).startsWith("ltxn_").hasSize(31);
        assertThat(second).startsWith("ltxn_").hasSize(31).isNotEqualTo(first);
    }
}
