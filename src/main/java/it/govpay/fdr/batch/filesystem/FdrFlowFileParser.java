package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.nio.file.Path;

import it.govpay.fdr.batch.dto.FdrFlowFile;

/**
 * Lettura di un file depositato nella directory di acquisizione in uno dei formati
 * supportati.
 * <p>
 * Ogni formato produce lo stesso {@link FdrFlowFile}: validazione, deduplica e
 * persistenza restano quindi indipendenti dal formato del file.
 */
public interface FdrFlowFileParser {

    /**
     * @param nomeFile nome con cui il file e' stato depositato
     * @return {@code true} se il parser gestisce il file
     */
    boolean supporta(String nomeFile);

    /**
     * Legge il file.
     *
     * @throws IOException      se il file non e' leggibile
     * @throws RuntimeException se il contenuto non e' nel formato atteso; il messaggio
     *                          deve spiegare all'operatore cosa non va
     */
    FdrFlowFile leggi(Path file) throws IOException;

    /**
     * @return nome del formato, per log e diagnostica
     */
    String formato();

    /**
     * @return {@code false} se il tracciato non porta la revisione del flusso, che va
     *         quindi calcolata dalla base dati prima dell'acquisizione
     */
    default boolean revisioneNelTracciato() {
        return true;
    }
}
