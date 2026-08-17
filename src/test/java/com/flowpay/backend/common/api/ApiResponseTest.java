package com.flowpay.backend.common.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void shouldWrapSingleValueWithoutMeta() {
        ApiResponse<String> response = ApiResponse.of("ok");

        assertThat(response.data()).isEqualTo("ok");
        assertThat(response.meta()).isNull();
    }

    @Test
    void shouldCreatePaginationMetadata() {
        PageImpl<String> page = new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7);

        ApiResponse<List<String>> response = ApiResponse.page(page);

        assertThat(response.data()).containsExactly("a", "b");
        assertThat(response.meta()).isEqualTo(new PageMeta(1, 2, 7, 4, true, true));
    }
}
