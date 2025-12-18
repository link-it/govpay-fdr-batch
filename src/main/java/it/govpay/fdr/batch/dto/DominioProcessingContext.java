package it.govpay.fdr.batch.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Context information for processing a single domain
 */
@Data
@Builder
public class DominioProcessingContext {
    private Long dominioId;
    private String codDominio;
    private LocalDateTime lastPublicationDate;
}
