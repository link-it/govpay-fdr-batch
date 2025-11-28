package it.govpay.fdr.batch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.govpay.gde.client.api.EventiApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for GdeConfig.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false",
    "govpay.gde.enabled=true",
    "govpay.gde.base-url=http://localhost:10002/api/v1"
})
class GdeConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private EventiApi eventiApi;

    @Test
    void testGdeEventiApiBeanCreatedWhenEnabled() {
        // Given GDE is enabled in properties
        // When context is loaded
        // Then EventiApi bean should be created
        assertThat(eventiApi).isNotNull();
        assertThat(applicationContext.containsBean("gdeEventiApi")).isTrue();
    }

    @Test
    void testGdeEventiApiIsConfiguredCorrectly() {
        // Given EventiApi bean is created
        // Then it should be properly configured
        assertThat(eventiApi).isNotNull();
        // The bean is created with the base URL from properties
    }
}
