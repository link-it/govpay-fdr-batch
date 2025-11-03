package it.govpay.fdr.batch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration tests for GovpayFdrBatchApplication
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.batch.job.enabled=false"
})
class GovpayFdrBatchApplicationTests {

    @Test
    void contextLoads() {
        // Test that the Spring context loads successfully
    }
}
