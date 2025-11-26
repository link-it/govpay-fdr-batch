package it.govpay.fdr.batch.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.govpay.gde.client.ApiClient;
import it.govpay.gde.client.api.EventiApi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * Configuration class for GDE (Eventi) API client.
 * <p>
 * This configuration creates the EventiApi bean used to send events to the GDE microservice.
 * The bean is only created if the GDE service is enabled via property govpay.gde.enabled=true.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "govpay.gde.enabled", havingValue = "true", matchIfMissing = false)
public class GdeConfig {

    @Value("${govpay.gde.base-url}")
    private String baseUrl;

    /**
     * Creates the EventiApi bean for sending events to GDE.
     *
     * @param objectMapper Jackson ObjectMapper for JSON serialization
     * @return EventiApi configured with base URL
     */
    @Bean("gdeEventiApi")
    public EventiApi gdeEventiApi(ObjectMapper objectMapper) {
        log.info("Initializing GDE EventiApi with base URL: {}", baseUrl);

        HttpClient.Builder builder = HttpClient.newBuilder();
        ApiClient apiClient = new ApiClient(builder, objectMapper, baseUrl);

        return new EventiApi(apiClient);
    }
}
