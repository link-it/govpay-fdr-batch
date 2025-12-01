package it.govpay.fdr.batch.step2;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.repository.DominioRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;

/**
 * Reader for enabled domains to fetch FDR headers
 */
@Component
@Slf4j
public class FdrHeadersReader implements ItemReader<DominioProcessingContext> {

    private final DominioRepository dominioRepository;
    private Iterator<Object[]> dominioIterator;
    private boolean initialized = false;

    public FdrHeadersReader(DominioRepository dominioRepository) {
        this.dominioRepository = dominioRepository;
    }

    @Override
    public DominioProcessingContext read() {
        if (!initialized) {
            List<Object[]> dominiInfos = dominioRepository.findDominioWithMaxDataOraPubblicazione();
            log.info("Found {} enabled domains to process", dominiInfos.size());
            dominioIterator = dominiInfos.iterator();
            initialized = true;
        }

        if (dominioIterator != null && dominioIterator.hasNext()) {
            Object[] dominioInfos = dominioIterator.next();
            Dominio dominio = (Dominio)dominioInfos[0];
            log.debug("Reading domain: {}", dominio.getCodDominio());

            return DominioProcessingContext.builder()
                .dominioId(dominio.getId())
                .codDominio(dominio.getCodDominio())
                .lastPublicationDate((Instant)dominioInfos[1])
                .build();
        }

        log.info("Nessun altro dominio da processare");
        return null; // End of data
    }
}
