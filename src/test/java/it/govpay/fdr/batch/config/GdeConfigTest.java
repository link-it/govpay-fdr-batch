package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.govpay.gde.client.api.EventiApi;

/**
 * Test class for GdeConfig.
 */
class GdeConfigTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
        "spring.batch.job.enabled=false",
        "govpay.gde.enabled=true",
        "govpay.gde.base-url=http://localhost:10002/api/v1"
    })
    class WhenGdeEnabledWithValidUrl {

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

    @Nested
    class UnitTestGdeConfig {

        @Test
        void testGdeEventiApiReturnsNullWhenBaseUrlIsNull() {
            // Given
            GdeConfig gdeConfig = new GdeConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(gdeConfig, "baseUrl", null);
            ObjectMapper objectMapper = new ObjectMapper();

            // When
            EventiApi result = gdeConfig.gdeEventiApi(objectMapper);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void testGdeEventiApiReturnsNullWhenBaseUrlIsEmpty() {
            // Given
            GdeConfig gdeConfig = new GdeConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(gdeConfig, "baseUrl", "");
            ObjectMapper objectMapper = new ObjectMapper();

            // When
            EventiApi result = gdeConfig.gdeEventiApi(objectMapper);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void testGdeEventiApiReturnsNullWhenBaseUrlIsBlank() {
            // Given
            GdeConfig gdeConfig = new GdeConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(gdeConfig, "baseUrl", "   ");
            ObjectMapper objectMapper = new ObjectMapper();

            // When
            EventiApi result = gdeConfig.gdeEventiApi(objectMapper);

            // Then
            assertThat(result).isNull();
        }

        @Test
        void testGdeEventiApiCreatedWhenBaseUrlIsValid() {
            // Given
            GdeConfig gdeConfig = new GdeConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(gdeConfig, "baseUrl", "http://localhost:10002/api/v1");
            ObjectMapper objectMapper = new ObjectMapper();

            // When
            EventiApi result = gdeConfig.gdeEventiApi(objectMapper);

            // Then
            assertThat(result).isNotNull();
        }
    }
}
