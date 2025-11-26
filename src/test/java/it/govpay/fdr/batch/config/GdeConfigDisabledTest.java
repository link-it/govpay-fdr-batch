package it.govpay.fdr.batch.config;

import it.govpay.gde.client.api.EventiApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for GdeConfig when GDE is disabled.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=false"
})
class GdeConfigDisabledTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private EventiApi eventiApi;

    @Test
    void testGdeEventiApiBeanNotCreatedWhenDisabled() {
        // Given GDE is disabled in properties
        // When context is loaded
        // Then EventiApi bean should NOT be created
        assertThat(eventiApi).isNull();
        assertThat(applicationContext.containsBean("gdeEventiApi")).isFalse();
    }
}
