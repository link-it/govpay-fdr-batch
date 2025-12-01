package it.govpay.fdr.batch.step4;

import it.govpay.fdr.batch.config.BatchProperties;
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
    private final int pageSize;
    private Iterator<FrTemp> currentPageIterator;
    private int currentPage = 0;
    private boolean initialized = false;

    public FdrPaymentsReader(FrTempRepository frTempRepository, BatchProperties batchProperties) {
        this.frTempRepository = frTempRepository;
        this.pageSize = batchProperties.getPaymentsChunkSize();
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
            log.debug("Lettura FR_TEMP dal DB - Flusso: {}, IUR: {}, Dominio: {}, PSP: {}, Revisione: {}, NumPagamenti: {}, ImportoTotale: {}, DataPubblicazione: {}",
                frTemp.getCodFlusso(),
                frTemp.getIur(),
                frTemp.getCodDominio(),
                frTemp.getCodPsp(),
                frTemp.getRevisione(),
                frTemp.getNumeroPagamenti(),
                frTemp.getImportoTotalePagamenti(),
                frTemp.getDataOraPubblicazione());
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
