package com.flowpay.backend.ledger.infrastructure.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UlidLedgerAccountPublicIdGeneratorTest {

    @Test
    void shouldGeneratePrefixedUniquePublicIds() {
        UlidLedgerAccountPublicIdGenerator generator =
                new UlidLedgerAccountPublicIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).startsWith("la_").hasSize(29);
        assertThat(second).startsWith("la_").hasSize(29).isNotEqualTo(first);
    }
}
