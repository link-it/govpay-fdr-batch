package it.govpay.fdr.batch.filesystem;

import java.util.List;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.FdrCompleteData;
import it.govpay.fdr.batch.step4.FdrPaymentsWriter;

/**
 * Persiste un singolo flusso letto da file system riusando il writer dello step 4.
 * <p>
 * La transazione e' {@code REQUIRES_NEW} di proposito: l'esito di un file non deve
 * dipendere da quello degli altri, e il writer chiamante deve poter intercettare
 * l'errore e archiviare il file fra gli scarti senza trovarsi la transazione dello
 * step gia' marcata per il rollback.
 */
@Component
public class FdrFileSystemPersister {

    private final FdrPaymentsWriter paymentsWriter;

    public FdrFileSystemPersister(FdrPaymentsWriter paymentsWriter) {
        this.paymentsWriter = paymentsWriter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persisti(FdrCompleteData data) {
        paymentsWriter.write(new Chunk<>(List.of(data)));
    }
}
