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
     * Cron expression for batch scheduling (default: every day at 2 AM)
     */
    private String cron = "0 0 2 * * ?";

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
}
