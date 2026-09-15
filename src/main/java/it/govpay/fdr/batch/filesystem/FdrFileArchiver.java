package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.config.FdrInputProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * Sposta i file elaborati fuori dalla directory di acquisizione.
 * <p>
 * Un file non viene mai cancellato: finisce in {@code processed} se il flusso e' stato
 * acquisito (o era gia' presente in FR) e in {@code error} se e' stato scartato, in
 * quest'ultimo caso accompagnato da un {@code .error.txt} con la motivazione.
 * Se l'archiviazione fallisce il file resta dov'e', con il suffisso di presa in carico:
 * l'operatore lo vede e non viene comunque rielaborato.
 */
@Component
@Slf4j
public class FdrFileArchiver {

    private static final DateTimeFormatter SUFFISSO_ANTICOLLISIONE =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private static final String ESTENSIONE_ERRORE = ".error.txt";

    private final FdrInputProperties inputProperties;

    public FdrFileArchiver(FdrInputProperties inputProperties) {
        this.inputProperties = inputProperties;
    }

    /**
     * Archivia un file il cui flusso e' stato acquisito o era gia' presente in FR.
     */
    public void archiviaProcessato(Path file, String nomeOriginale) {
        archivia(file, nomeOriginale, inputProperties.getProcessedDirPath());
    }

    /**
     * Archivia un file scartato, scrivendo accanto un {@code .error.txt} con la motivazione.
     */
    public void archiviaScartato(Path file, String nomeOriginale, String motivo) {
        Path archiviato = archivia(file, nomeOriginale, inputProperties.getErrorDirPath());
        if (archiviato == null) {
            return;
        }
        Path descrizione = archiviato.resolveSibling(archiviato.getFileName() + ESTENSIONE_ERRORE);
        try {
            Files.writeString(descrizione, motivo + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Impossibile scrivere la motivazione dello scarto in {}: {}", descrizione, e.getMessage());
        }
    }

    private Path archivia(Path file, String nomeOriginale, Path destinazione) {
        try {
            Files.createDirectories(destinazione);
            Path target = destinazioneLibera(destinazione, nomeOriginale);
            Files.move(file, target);
            log.info("File {} archiviato in {}", nomeOriginale, target.toAbsolutePath());
            return target;
        } catch (IOException e) {
            log.error("Impossibile archiviare il file {} in {}: {}. Il file resta in carico a questo nodo"
                    + " e va spostato manualmente.", nomeOriginale, destinazione.toAbsolutePath(), e.getMessage());
            return null;
        }
    }

    /**
     * Il nome originale viene mantenuto; se in archivio esiste gia' un file con quel nome
     * (stesso flusso ricaricato piu' volte) si aggiunge un suffisso temporale, senza
     * sovrascrivere nulla.
     */
    private Path destinazioneLibera(Path destinazione, String nomeOriginale) {
        Path target = destinazione.resolve(nomeOriginale);
        if (!Files.exists(target)) {
            return target;
        }
        String suffisso = "-" + LocalDateTime.now().format(SUFFISSO_ANTICOLLISIONE);
        int punto = nomeOriginale.lastIndexOf('.');
        String nomeUnivoco = punto > 0
            ? nomeOriginale.substring(0, punto) + suffisso + nomeOriginale.substring(punto)
            : nomeOriginale + suffisso;
        return destinazione.resolve(nomeUnivoco);
    }
}
