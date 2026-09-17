package it.govpay.fdr.batch.dto;

import java.nio.file.Path;

import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.FdrCompleteData;

/**
 * Esito dell'analisi di un singolo file depositato nella directory di acquisizione.
 * <p>
 * L'item attraversa sempre il writer, anche quando il flusso non va acquisito: e' il
 * writer ad archiviare il file, e un item filtrato (processor che ritorna {@code null})
 * lascerebbe il file bloccato nello stato "preso in carico".
 *
 * @param file          file preso in carico dal nodo (con suffisso di lock)
 * @param nomeOriginale nome del file cosi' come depositato dall'operatore
 * @param esito         cosa farne
 * @param data          dati del flusso, valorizzati solo se {@code esito == ACQUISIRE}
 * @param codDominio    dominio del flusso, se estraibile dal file
 * @param codFlusso     identificativo del flusso, se estraibile dal file
 * @param motivo        descrizione dello scarto o della deduplica
 */
public record FdrFileItem(
    Path file,
    String nomeOriginale,
    Esito esito,
    FdrCompleteData data,
    String codDominio,
    String codFlusso,
    String motivo
) {

    public enum Esito {
        /** Flusso nuovo e valido: da persistere su FR/RENDICONTAZIONI. */
        ACQUISIRE,
        /** Flusso gia' presente in FR: il file viene archiviato senza reinserimento. */
        DUPLICATO,
        /** File malformato, incompleto o riferito a un dominio non acquisibile. */
        SCARTATO
    }

    public static FdrFileItem acquisire(Path file, String nomeOriginale, FdrCompleteData data) {
        return new FdrFileItem(file, nomeOriginale, Esito.ACQUISIRE, data,
            data.getCodDominio(), data.getCodFlusso(), null);
    }

    public static FdrFileItem duplicato(Path file, String nomeOriginale, String codDominio,
                                        String codFlusso, String motivo) {
        return new FdrFileItem(file, nomeOriginale, Esito.DUPLICATO, null, codDominio, codFlusso, motivo);
    }

    public static FdrFileItem scartato(Path file, String nomeOriginale, String codDominio,
                                       String codFlusso, String motivo) {
        return new FdrFileItem(file, nomeOriginale, Esito.SCARTATO, null, codDominio, codFlusso, motivo);
    }
}
