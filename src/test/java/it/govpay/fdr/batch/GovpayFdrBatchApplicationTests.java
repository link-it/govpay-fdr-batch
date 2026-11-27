package it.govpay.fdr.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Integration tests for GovpayFdrBatchApplication
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.batch.job.enabled=false"
})
class GovpayFdrBatchApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoads() {
        // Test that the Spring context loads successfully
    }

    /**
     * #79: il pool usa READ_COMMITTED come il core GovPay, non il default del database.
     */
    @Test
    void isolamentoReadCommitted() throws SQLException {
        assertThat(dataSource.unwrap(HikariDataSource.class).getTransactionIsolation())
                .isEqualTo("TRANSACTION_READ_COMMITTED");
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        }
    }
}
