package it.govpay.fdr.batch.step2;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Writer to save FDR headers to FR_TEMP table and update domain last acquisition date
 */
@Component
@Slf4j
public class FdrHeadersWriter implements ItemWriter<FdrHeadersBatch> {

    public static final String STATS_SAVED_COUNT = "headersSavedCount";
    public static final String STATS_SKIPPED_FR_COUNT = "headersSkippedFrCount";
    public static final String STATS_SKIPPED_FR_TEMP_COUNT = "headersSkippedFrTempCount";

    private final FrTempRepository frTempRepository;
    private final FrRepository frRepository;
    private StepExecution stepExecution;

    public FdrHeadersWriter(FrTempRepository frTempRepository, FrRepository frRepository) {
        this.frTempRepository = frTempRepository;
        this.frRepository = frRepository;
    }

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        // Inizializza i contatori nel contesto
        stepExecution.getExecutionContext().putInt(STATS_SAVED_COUNT, 0);
        stepExecution.getExecutionContext().putInt(STATS_SKIPPED_FR_COUNT, 0);
        stepExecution.getExecutionContext().putInt(STATS_SKIPPED_FR_TEMP_COUNT, 0);
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrHeadersBatch> chunk) {
        for (FdrHeadersBatch batch : chunk) {
            log.info("Scrittura di {} header FDR per il dominio {}", batch.getHeaders().size(), batch.getCodDominio());

            HeaderProcessingStats stats = new HeaderProcessingStats();

            for (FdrHeadersBatch.FdrHeader header : batch.getHeaders()) {
                processHeader(batch.getCodDominio(), header, stats);
            }

            // Aggiorna le statistiche nel contesto dello step
            updateStepContextStats(stats);

            log.info("Dominio {}: salvati {} nuovi FDR, saltati {} già in FR, saltati {} già in FR_TEMP",
                batch.getCodDominio(), stats.savedCount, stats.alreadyInFrCount, stats.alreadyInFrTempCount);
        }
    }

    private void updateStepContextStats(HeaderProcessingStats stats) {
        if (stepExecution != null) {
            int currentSaved = stepExecution.getExecutionContext().getInt(STATS_SAVED_COUNT, 0);
            int currentSkippedFr = stepExecution.getExecutionContext().getInt(STATS_SKIPPED_FR_COUNT, 0);
            int currentSkippedFrTemp = stepExecution.getExecutionContext().getInt(STATS_SKIPPED_FR_TEMP_COUNT, 0);

            stepExecution.getExecutionContext().putInt(STATS_SAVED_COUNT, currentSaved + stats.savedCount);
            stepExecution.getExecutionContext().putInt(STATS_SKIPPED_FR_COUNT, currentSkippedFr + stats.alreadyInFrCount);
            stepExecution.getExecutionContext().putInt(STATS_SKIPPED_FR_TEMP_COUNT, currentSkippedFrTemp + stats.alreadyInFrTempCount);
        }
    }

    /**
     * Processes a single FDR header and updates statistics.
     * Extracted to avoid multiple continue statements (SonarQube java:S135).
     *
     * @param codDominio the domain code
     * @param header the FDR header to process
     * @param stats statistics object to update
     */
    private void processHeader(String codDominio, FdrHeadersBatch.FdrHeader header, HeaderProcessingStats stats) {
        // Prima verifica: controllare se esiste già nella tabella definitiva FR
        // Questo evita chiamate API inutili verso pagoPA
        if (frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            codDominio,
            header.getCodFlusso(),
            header.getIdPsp(),
            header.getRevision()
        )) {
            log.debug("FDR {} già presente nella tabella FR definitiva per il dominio {} - saltato",
                header.getCodFlusso(), codDominio);
            stats.alreadyInFrCount++;
            return;
        }

        // Seconda verifica: controllare se esiste già in FR_TEMP
        if (frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            codDominio,
            header.getCodFlusso(),
            header.getIdPsp(),
            header.getRevision()
        )) {
            log.debug("FDR {} già presente in FR_TEMP - saltato", header.getCodFlusso());
            stats.alreadyInFrTempCount++;
            return;
        }

        // Nuovo flusso: inserire in FR_TEMP per elaborazione successiva
        FrTemp frTemp = FrTemp.builder()
            .codDominio(codDominio)
            .codFlusso(header.getCodFlusso())
            .idPsp(header.getIdPsp())
            .revisione(header.getRevision())
            .dataOraFlusso(header.getDataOraFlusso())
            .dataOraPubblicazione(header.getDataOraPubblicazione())
            .build();

        frTempRepository.save(frTemp);
        stats.savedCount++;
    }

    /**
     * Helper class to track header processing statistics.
     */
    private static class HeaderProcessingStats {
        int savedCount = 0;
        int alreadyInFrCount = 0;
        int alreadyInFrTempCount = 0;
    }
}
