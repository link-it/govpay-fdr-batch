package it.govpay.fdr.batch.step3;

import java.util.Iterator;

import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.config.BatchProperties;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Reader for FDR headers from FR_TEMP table
 */
@Component
@Slf4j
public class FdrMetadataReader implements ItemReader<FrTemp> {

    private final FrTempRepository frTempRepository;
    private final BatchProperties batchProperties;
    private Iterator<FrTemp> currentPageIterator;
    private int currentPage = 0;
    private int pageSize = 50;
    private boolean initialized = false;

    public FdrMetadataReader(FrTempRepository frTempRepository, BatchProperties batchProperties) {
        this.frTempRepository = frTempRepository;
        this.batchProperties = batchProperties;
        this.pageSize = this.batchProperties.getMetadataChunkSize();
    }

    @Override
    public FrTemp read() {
        if (!initialized) {
            log.info("Initializing FdrMetadataReader");
            initialized = true;
            loadNextPage();
        }

        if (currentPageIterator != null && currentPageIterator.hasNext()) {
            FrTemp frTemp = currentPageIterator.next();
            log.debug("Reading FR_TEMP record: {} - {} - revision {}",
                frTemp.getCodDominio(), frTemp.getCodFlusso(), frTemp.getRevisione());
            return frTemp;
        } else if (loadNextPage()) {
            return read();
        }

        log.info("Nessun altro record FR_TEMP da processare");
        return null; // End of data
    }

    private boolean loadNextPage() {
        Pageable pageable = PageRequest.of(currentPage, pageSize);
        Page<FrTemp> page = frTempRepository.findByOrderByDataOraPubblicazioneAsc(pageable);

        if (!page.isEmpty()) {
            currentPageIterator = page.getContent().iterator();
            currentPage++;
            log.debug("Caricata pagina {} con {} record", currentPage, page.getContent().size());
            return true;
        }

        return false;
    }
}
