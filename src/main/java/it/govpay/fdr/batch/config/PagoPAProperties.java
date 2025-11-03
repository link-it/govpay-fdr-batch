package it.govpay.fdr.batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for pagoPA FDR API
 */
@Configuration
@ConfigurationProperties(prefix = "pagopa.fdr")
@Data
public class PagoPAProperties {

    /**
     * Base URL for pagoPA FDR API
     */
    private String baseUrl = "https://api.platform.pagopa.it/fdr-org/service/v1";

    /**
     * Subscription key for API authentication
     */
    private String subscriptionKey;

    /**
     * Subscription key header name
     */
    private String subscriptionKeyHeader = "Ocp-Apim-Subscription-Key";

    /**
     * Connection timeout in milliseconds
     */
    private int connectionTimeout = 10000;

    /**
     * Read timeout in milliseconds
     */
    private int readTimeout = 30000;

    /**
     * Number of retries for failed API calls
     */
    private int maxRetries = 3;

    /**
     * Page size for paginated requests
     */
    private int pageSize = 1000;
}
