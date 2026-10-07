package it.govpay.fdr.batch.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

/**
 * Proprieta' di configurazione dell'acquisizione dei flussi di rendicontazione
 * da directory sul file system.
 * <p>
 * Il canale e' disabilitato di default: con {@code govpay.fdr.input.enabled=false}
 * lo step non viene aggiunto al job e il comportamento del batch resta invariato.
 */
@Configuration
@ConfigurationProperties(prefix = "govpay.fdr.input")
@Data
public class FdrInputProperties {

    /** Suffisso usato per marcare i file presi in carico da un nodo. */
    public static final String PROCESSING_SUFFIX = ".processing";

    /** Nome di default della sottodirectory dei file acquisiti, se non configurata. */
    private static final String DEFAULT_PROCESSED_DIR = "processed";

    /** Nome di default della sottodirectory dei file scartati, se non configurata. */
    private static final String DEFAULT_ERROR_DIR = "error";

    /**
     * Abilita l'acquisizione dei flussi da file system.
     */
    private boolean enabled = false;

    /**
     * Directory da cui vengono letti i flussi da acquisire.
     * Obbligatoria quando {@link #enabled} e' {@code true}.
     */
    private String dir;

    /**
     * Directory in cui vengono archiviati i file acquisiti con successo
     * (o gia' presenti in FR). Default: {@code <dir>/processed}.
     */
    private String processedDir;

    /**
     * Directory in cui vengono archiviati i file scartati, insieme a un file
     * {@code .error.txt} con la motivazione. Default: {@code <dir>/error}.
     */
    private String errorDir;

    /**
     * Estensione dei file da acquisire.
     */
    private String extension = ".json";

    /**
     * Numero massimo di file elaborati in una singola esecuzione del job.
     * Evita che un travaso massivo nella directory monopolizzi il batch.
     */
    private int maxFilesPerRun = 1000;

    /**
     * La directory di acquisizione e' facoltativa anche a canale abilitato: se non e'
     * configurata lo step non ha nulla da fare e si limita a non produrre item.
     */
    public boolean isDirConfigurata() {
        return dir != null && !dir.isBlank();
    }

    /**
     * @return la directory di acquisizione, oppure {@code null} se non configurata
     */
    public Path getDirPath() {
        return isDirConfigurata() ? Path.of(dir) : null;
    }

    /**
     * @return la destinazione dei file acquisiti, oppure {@code null} se non ricavabile
     */
    public Path getProcessedDirPath() {
        return resolveOrDefault(processedDir, DEFAULT_PROCESSED_DIR);
    }

    /**
     * @return la destinazione dei file scartati, oppure {@code null} se non ricavabile
     */
    public Path getErrorDirPath() {
        return resolveOrDefault(errorDir, DEFAULT_ERROR_DIR);
    }

    private Path resolveOrDefault(String configured, String defaultName) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path inputDir = getDirPath();
        return inputDir != null ? inputDir.resolve(defaultName) : null;
    }
}
