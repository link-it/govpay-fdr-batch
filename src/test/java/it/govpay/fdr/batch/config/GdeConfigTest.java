package it.govpay.fdr.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import it.govpay.common.configurazione.service.ConfigurazioneService;
import it.govpay.common.gde.AbstractGdeService;
import it.govpay.fdr.batch.gde.service.GdeService;

/**
 * Integration test for GDE configuration in the Spring context.
 * Verifies that GdeService is correctly wired with ConfigurazioneService
 * and reads connector settings from the database.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.batch.job.enabled=false"
})
class GdeConfigTest {

    @Autowired
    private GdeService gdeService;

    @Autowired
    private ConfigurazioneService configurazioneService;

    @Test
    @DisplayName("GdeService bean should be created in the application context")
    void testGdeServiceBeanExists() {
        assertThat(gdeService).isNotNull();
    }

    @Test
    @DisplayName("GdeService should extend AbstractGdeService from govpay-common")
    void testGdeServiceExtendsAbstractGdeService() {
        assertThat(gdeService).isInstanceOf(AbstractGdeService.class);
    }

    @Test
    @DisplayName("ConfigurazioneService bean should be created by govpay-common auto-config")
    void testConfigurazioneServiceBeanExists() {
        assertThat(configurazioneService).isNotNull();
    }

    @Test
    @DisplayName("GDE connector should be enabled when ABILITATO=true in DB")
    void testGdeAbilitatoFromDatabase() {
        // import.sql sets govpay_gde_api ABILITATO=true
        assertThat(gdeService.isAbilitato()).isTrue();
    }

    @Test
    @DisplayName("GDE endpoint URL should be read from connector in DB")
    void testGdeEndpointFromDatabase() {
        // import.sql sets govpay_gde_api URL=http://localhost:10002/api/v1
        String gdeUrl = configurazioneService.getServizioGDE().getUrl();
        assertThat(gdeUrl).isEqualTo("http://localhost:10002/api/v1");
    }

    @Test
    @DisplayName("GDE RestTemplate should be provided by ConfigurazioneService")
    void testGdeRestTemplateAvailable() {
        assertThat(configurazioneService.getRestTemplateGDE()).isNotNull();
    }
}
