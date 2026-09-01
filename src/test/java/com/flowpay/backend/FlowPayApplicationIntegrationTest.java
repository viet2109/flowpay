package com.flowpay.backend;

import com.flowpay.backend.testing.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlowPayApplicationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void shouldStartWithRealPostgresAndApplyFlywayMigrations() {
        Integer databaseResult = jdbcTemplate.queryForObject("select 1", Integer.class);
        Integer latestMigrationCount = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '007' and success = true",
                Integer.class
        );

        assertThat(databaseResult).isEqualTo(1);
        assertThat(latestMigrationCount).isEqualTo(1);
    }
}
