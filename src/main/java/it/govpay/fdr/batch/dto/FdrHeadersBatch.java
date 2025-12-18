package it.govpay.fdr.batch.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Batch of FDR headers retrieved from pagoPA API
 */
@Data
@Builder
public class FdrHeadersBatch {
    private String codDominio;
    private List<FdrHeader> headers;

    @Data
    @Builder
    public static class FdrHeader {
        private String codFlusso;
        private String idPsp;
        private Long revision;
        private LocalDateTime dataOraFlusso;
        private LocalDateTime dataOraPubblicazione;
    }
}
