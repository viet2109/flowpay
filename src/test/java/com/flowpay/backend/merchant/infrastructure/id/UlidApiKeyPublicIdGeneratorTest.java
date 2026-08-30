package com.flowpay.backend.merchant.infrastructure.id;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UlidApiKeyPublicIdGeneratorTest {

    @Test
    void shouldGeneratePrefixedUniquePublicIds() {
        UlidApiKeyPublicIdGenerator generator = new UlidApiKeyPublicIdGenerator();

        String first = generator.nextId();
        String second = generator.nextId();

        assertThat(first).startsWith("key_").hasSize(30);
        assertThat(second).startsWith("key_").hasSize(30).isNotEqualTo(first);
    }
}
