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

    /**
     * Enable debug logging for API client (logs HTTP requests/responses)
     */
    private boolean debugging = false;

    /**
     * Massima "eta'" (in giorni) accettata da pagoPA per il parametro publishedGt.
     * L'API restituisce HTTP 400 (FDR-1000, "The date cannot be older than 30 days")
     * se publishedGt e' piu' vecchia di questo valore. Usata sia per rilevare la
     * condizione sia come target quando la strategia e' CLAMP.
     */
    private int publishedGtMaxAgeDays = 30;

    /**
     * Strategia da applicare quando la publishedGt calcolata (ultima pubblicazione
     * gia' acquisita per il dominio) e' piu' vecchia di {@link #publishedGtMaxAgeDays}.
     * <ul>
     *   <li>{@code ALL} (default): non invia publishedGt (null) e recupera tutti i flussi;</li>
     *   <li>{@code CLAMP}: riporta publishedGt a (adesso - publishedGtMaxAgeDays).</li>
     * </ul>
     */
    private PublishedGtStaleStrategy publishedGtStaleStrategy = PublishedGtStaleStrategy.ALL;

    /**
     * Strategie di gestione della publishedGt oltre la finestra accettata da pagoPA.
     */
    public enum PublishedGtStaleStrategy {
        /** Recupera tutti i flussi pubblicati (publishedGt = null). */
        ALL,
        /** Riporta publishedGt al limite consentito (adesso - publishedGtMaxAgeDays). */
        CLAMP
    }
}
