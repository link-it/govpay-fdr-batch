package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
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

    public FdrHeadersWriter(FrTempRepository frTempRepository) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrHeadersBatch> chunk) {
        for (FdrHeadersBatch batch : chunk) {
            log.info("Writing {} FDR headers for domain {}", batch.getHeaders().size(), batch.getCodDominio());

            int savedCount = 0;

            for (FdrHeadersBatch.FdrHeader header : batch.getHeaders()) {
                // Check if already exists
                if (!frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
                    batch.getCodDominio(),
                    header.getCodFlusso(),
                    header.getIdPsp(),
                    header.getRevision()
                )) {
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
            }

            log.info("Saved {} new FDR headers for domain {} (skipped {} duplicates)", savedCount, batch.getCodDominio(), batch.getHeaders().size() - savedCount);
        }
    }
}
