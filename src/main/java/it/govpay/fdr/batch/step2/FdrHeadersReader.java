package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.repository.DominioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;

/**
 * Reader for enabled domains to fetch FDR headers
 */
@Component
@Slf4j
public class FdrHeadersReader implements ItemReader<DominioProcessingContext> {

    private final DominioRepository dominioRepository;
    private Iterator<Dominio> dominioIterator;
    private boolean initialized = false;

    public FdrHeadersReader(DominioRepository dominioRepository) {
        this.dominioRepository = dominioRepository;
    }

    @Override
    public DominioProcessingContext read() {
        if (!initialized) {
            List<Dominio> domini = dominioRepository.findByAbilitatoTrue();
            log.info("Found {} enabled domains to process", domini.size());
            dominioIterator = domini.iterator();
            initialized = true;
        }

        if (dominioIterator != null && dominioIterator.hasNext()) {
            Dominio dominio = dominioIterator.next();
            log.debug("Reading domain: {}", dominio.getCodDominio());

            return DominioProcessingContext.builder()
                .dominioId(dominio.getId())
                .codDominio(dominio.getCodDominio())
                .lastPublicationDate(dominio.getDataUltimaAcquisizione())
                .build();
        }

        log.info("No more domains to process");
        return null; // End of data
    }
}
