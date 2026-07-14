package it.govpay.fdr.batch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for batch processing
 */
@Configuration
@ConfigurationProperties(prefix = "govpay.batch")
@Data
public class BatchProperties {

    /**
     * Thread pool size for parallel processing (Step 2)
     */
    private int threadPoolSize = 5;

    /**
     * Chunk size for Step 2 - Headers Acquisition
     */
    private int headersChunkSize = 1;

    /**
     * Chunk size for Step 3 - Metadata Acquisition
     */
    private int metadataChunkSize = 100;

    /**
     * Chunk size for Step 4 - Payments Acquisition
     */
    private int paymentsChunkSize = 50;

    /**
     * Skip limit for failed items
     */
    private int skipLimit = 10;

    /**
     * Enable/disable automatic scheduling
     */
    private boolean enabled = true;

    /**
     * Number of retries for failed API calls
     */
    private int maxRetries = 3;

    /**
     * Page size for paginated requests to pagoPA API
     */
    private int pageSize = 1000;

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
