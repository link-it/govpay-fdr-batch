package it.govpay.fdr.batch.step3;

import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Iterator;

/**
 * Reader for unprocessed FDR headers from FR_TEMP table
 */
@Component
@Slf4j
public class FdrPaymentsReader implements ItemReader<FrTemp> {

    private final FrTempRepository frTempRepository;
    private Iterator<FrTemp> currentPageIterator;
    private int currentPage = 0;
    private final int pageSize = 50;
    private boolean initialized = false;

    public FdrPaymentsReader(FrTempRepository frTempRepository) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    public FrTemp read() {
        if (!initialized) {
            log.info("Initializing FdrPaymentsReader");
            initialized = true;
            loadNextPage();
        }

        if (currentPageIterator != null && currentPageIterator.hasNext()) {
            FrTemp frTemp = currentPageIterator.next();
            log.debug("Reading FR_TEMP record: {} - {} - revision {}",
                frTemp.getCodDominio(), frTemp.getCodFlusso(), frTemp.getRevision());
            return frTemp;
        } else if (loadNextPage()) {
            return read();
        }

        log.info("No more FR_TEMP records to process");
        return null; // End of data
    }

    private boolean loadNextPage() {
        Pageable pageable = PageRequest.of(currentPage, pageSize);
        Page<FrTemp> page = frTempRepository.findByProcessatoFalseOrderByDataPubblicazioneAsc(pageable);

        if (!page.isEmpty()) {
            currentPageIterator = page.getContent().iterator();
            currentPage++;
            log.debug("Loaded page {} with {} records", currentPage, page.getContent().size());
            return true;
        }

        return false;
    }
}
