package it.govpay.fdr.batch.step3;

import java.util.Iterator;
import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Reader per flussi FR_TEMP di una specifica partizione (dominio).
 * Legge TUTTI i flussi del dominio assegnato alla partizione.
 */
@Component
@StepScope
@Slf4j
public class FdrMetadataReader implements ItemReader<FrTemp>, ItemStream {

    private final FrTempRepository frTempRepository;

    @Value("#{stepExecutionContext['codDominio']}")
    private String codDominio;

    @Value("#{stepExecutionContext['partitionNumber']}")
    private Integer partitionNumber;

    @Value("#{stepExecutionContext['totalPartitions']}")
    private Integer totalPartitions;

    private Iterator<FrTemp> flussiIterator;
    private boolean initialized = false;

    public FdrMetadataReader(FrTempRepository frTempRepository) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        if (!initialized) {
            log.info("Inizializzazione partizione {}/{} per dominio: {}",
                partitionNumber, totalPartitions, codDominio);

            // Carica TUTTI i flussi di questo dominio
            List<FrTemp> flussi = frTempRepository.findByCodDominioOrderByDataOraPubblicazioneAsc(codDominio);

            log.info("Partizione {} (dominio {}): trovati {} flussi da processare",
                partitionNumber, codDominio, flussi.size());

            flussiIterator = flussi.iterator();
            initialized = true;
        }
    }

    @Override
    public FrTemp read() {
        if (flussiIterator != null && flussiIterator.hasNext()) {
            FrTemp frTemp = flussiIterator.next();
            log.debug("Lettura flusso per dominio {}: {} (revisione {})",
                codDominio, frTemp.getCodFlusso(), frTemp.getRevisione());
            return frTemp;
        }

        log.info("Partizione {} (dominio {}): completata lettura di tutti i flussi",
            partitionNumber, codDominio);
        return null; // End of partition data
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Niente da salvare nello stato
    }

    @Override
    public void close() throws ItemStreamException {
        // Cleanup se necessario
        flussiIterator = null;
    }
}
