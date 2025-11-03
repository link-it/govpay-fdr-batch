package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.DominioRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Writer to save FDR headers to FR_TEMP table and update domain last acquisition date
 */
@Component
@Slf4j
public class FdrHeadersWriter implements ItemWriter<FdrHeadersBatch> {

    private final FrTempRepository frTempRepository;
    private final DominioRepository dominioRepository;

    public FdrHeadersWriter(FrTempRepository frTempRepository, DominioRepository dominioRepository) {
        this.frTempRepository = frTempRepository;
        this.dominioRepository = dominioRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrHeadersBatch> chunk) {
        for (FdrHeadersBatch batch : chunk) {
            log.info("Writing {} FDR headers for domain {}", batch.getHeaders().size(), batch.getCodDominio());

            Instant maxPublicationDate = null;
            int savedCount = 0;

            for (FdrHeadersBatch.FdrHeader header : batch.getHeaders()) {
                // Check if already exists
                if (!frTempRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevision(
                    batch.getCodDominio(),
                    header.getCodFlusso(),
                    header.getCodPsp(),
                    header.getRevision()
                )) {
                    FrTemp frTemp = FrTemp.builder()
                        .codDominio(batch.getCodDominio())
                        .codFlusso(header.getCodFlusso())
                        .codPsp(header.getCodPsp())
                        .revision(header.getRevision())
                        .dataFlusso(header.getDataFlusso())
                        .dataPubblicazione(header.getDataPubblicazione())
                        .processato(false)
                        .build();

                    frTempRepository.save(frTemp);
                    savedCount++;
                }

                // Track maximum publication date
                if (header.getDataPubblicazione() != null &&
                    (maxPublicationDate == null || header.getDataPubblicazione().isAfter(maxPublicationDate))) {
                    maxPublicationDate = header.getDataPubblicazione();
                }
            }

            log.info("Saved {} new FDR headers for domain {} (skipped {} duplicates)",
                savedCount, batch.getCodDominio(), batch.getHeaders().size() - savedCount);

            // Update domain's last acquisition date
            if (maxPublicationDate != null) {
                updateDominioLastAcquisitionDate(batch.getCodDominio(), maxPublicationDate);
            }
        }
    }

    private void updateDominioLastAcquisitionDate(String codDominio, Instant maxPublicationDate) {
        Optional<Dominio> dominioOpt = dominioRepository.findByCodDominio(codDominio);
        if (dominioOpt.isPresent()) {
            Dominio dominio = dominioOpt.get();
            dominio.setDataUltimaAcquisizione(maxPublicationDate);
            dominioRepository.save(dominio);
            log.debug("Updated last acquisition date for domain {} to {}", codDominio, maxPublicationDate);
        } else {
            log.warn("Domain {} not found in database", codDominio);
        }
    }
}
