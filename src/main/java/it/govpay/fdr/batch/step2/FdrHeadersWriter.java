package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writer to save FDR headers to FR_TEMP table and update domain last acquisition date
 */
@Component
@Slf4j
public class FdrHeadersWriter implements ItemWriter<FdrHeadersBatch> {

    private final FrTempRepository frTempRepository;
    private final FrRepository frRepository;

    public FdrHeadersWriter(FrTempRepository frTempRepository, FrRepository frRepository) {
        this.frTempRepository = frTempRepository;
        this.frRepository = frRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrHeadersBatch> chunk) {
        for (FdrHeadersBatch batch : chunk) {
            log.info("Scrittura di {} header FDR per il dominio {}", batch.getHeaders().size(), batch.getCodDominio());

            int savedCount = 0;
            int alreadyInFrCount = 0;
            int alreadyInFrTempCount = 0;

            for (FdrHeadersBatch.FdrHeader header : batch.getHeaders()) {
                // Prima verifica: controllare se esiste già nella tabella definitiva FR
                // Questo evita chiamate API inutili verso pagoPA
                if (frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
                    batch.getCodDominio(),
                    header.getCodFlusso(),
                    header.getIdPsp(),
                    header.getRevision()
                )) {
                    log.debug("FDR {} già presente nella tabella FR definitiva per il dominio {} - saltato",
                        header.getCodFlusso(), batch.getCodDominio());
                    alreadyInFrCount++;
                    continue;
                }

                // Seconda verifica: controllare se esiste già in FR_TEMP
                if (frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
                    batch.getCodDominio(),
                    header.getCodFlusso(),
                    header.getIdPsp(),
                    header.getRevision()
                )) {
                    log.debug("FDR {} già presente in FR_TEMP - saltato", header.getCodFlusso());
                    alreadyInFrTempCount++;
                    continue;
                }

                // Nuovo flusso: inserire in FR_TEMP per elaborazione successiva
                FrTemp frTemp = FrTemp.builder()
                    .codDominio(batch.getCodDominio())
                    .codFlusso(header.getCodFlusso())
                    .idPsp(header.getIdPsp())
                    .revisione(header.getRevision())
                    .dataOraFlusso(header.getDataOraFlusso())
                    .dataOraPubblicazione(header.getDataOraPubblicazione())
                    .build();

                frTempRepository.save(frTemp);
                savedCount++;
            }

            log.info("Dominio {}: salvati {} nuovi FDR, saltati {} già in FR, saltati {} già in FR_TEMP",
                batch.getCodDominio(), savedCount, alreadyInFrCount, alreadyInFrTempCount);
        }
    }
}
