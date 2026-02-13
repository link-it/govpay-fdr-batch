package it.govpay.fdr.batch.config;

import it.govpay.fdr.batch.gde.service.GdeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for GDE configuration when GDE is disabled.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=false"
})
class GdeConfigDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private GdeService gdeService;

    @Test
    void testGdeServiceBeanNotCreatedWhenDisabled() {
        // Given GDE is disabled in properties
        // When context is loaded
        // Then GdeService bean should NOT be created
        assertThat(gdeService).isNull();
        assertThat(applicationContext.containsBean("gdeService")).isFalse();
    }
}
