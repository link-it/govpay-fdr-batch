package it.govpay.fdr.batch.dto;

import it.govpay.fdr.batch.entity.FrTemp;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Batch di flussi FrTemp raggruppati per dominio.
 * Tutti i flussi di questo batch appartengono allo stesso ente creditore.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FrTempBatch {

    /**
     * Codice dominio (ente creditore)
     */
    private String codDominio;

    /**
     * Lista di tutti i flussi FR_TEMP per questo dominio
     */
    private List<FrTemp> flussi;

    /**
     * Numero di flussi nel batch
     */
    public int size() {
        return flussi != null ? flussi.size() : 0;
    }
}
