package it.govpay.fdr.batch.filesystem;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemStream;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.config.FdrInputProperties;
import it.govpay.fdr.batch.dto.FdrClaimedFile;
import lombok.extern.slf4j.Slf4j;

/**
 * Reader dei flussi depositati nella directory di acquisizione.
 * <p>
 * Ogni file viene "preso in carico" rinominandolo in
 * {@code <nome>.<clusterId>.processing} con una move atomica: la rinomina e' il lock.
 * Se piu' nodi insistono sulla stessa directory condivisa, solo quello che riesce a
 * rinominare elabora il file; gli altri ricevono un errore dal file system e passano
 * al successivo. Un file rimasto con il suffisso di presa in carico segnala un nodo
 * terminato durante l'elaborazione: e' visibile all'operatore, che puo' rinominarlo
 * per rimetterlo in coda.
 */
@Component
@StepScope
@Slf4j
public class FdrFileSystemReader implements ItemReader<FdrClaimedFile>, ItemStream {

    private final FdrInputProperties inputProperties;
    private final String clusterId;

    private Deque<Path> candidati;
    private int presiInCarico;

    public FdrFileSystemReader(FdrInputProperties inputProperties,
                               @Value("${govpay.batch.cluster-id}") String clusterId) {
        this.inputProperties = inputProperties;
        this.clusterId = clusterId;
    }

    @Override
    public void open(ExecutionContext executionContext) throws ItemStreamException {
        candidati = new ArrayDeque<>();
        presiInCarico = 0;

        Path inputDir = inputProperties.getDirPath();
        try {
            Files.createDirectories(inputDir);
            Files.createDirectories(inputProperties.getProcessedDirPath());
            Files.createDirectories(inputProperties.getErrorDirPath());
        } catch (IOException e) {
            throw new ItemStreamException(
                "Impossibile predisporre le directory di acquisizione dei flussi da file system: " + e.getMessage(), e);
        }

        List<Path> files = elencaFile(inputDir);
        candidati.addAll(files);

        log.info("Acquisizione da file system: trovati {} file '{}' in {}",
            files.size(), inputProperties.getExtension(), inputDir.toAbsolutePath());
    }

    private List<Path> elencaFile(Path inputDir) {
        String estensione = inputProperties.getExtension();
        try (Stream<Path> stream = Files.list(inputDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(estensione))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .limit(inputProperties.getMaxFilesPerRun())
                .toList();
        } catch (IOException e) {
            throw new ItemStreamException(
                "Impossibile elencare i file nella directory di acquisizione " + inputDir.toAbsolutePath(), e);
        }
    }

    @Override
    public FdrClaimedFile read() {
        Path candidato;
        while ((candidato = candidati.poll()) != null) {
            FdrClaimedFile presoInCarico = prendiInCarico(candidato);
            if (presoInCarico != null) {
                presiInCarico++;
                return presoInCarico;
            }
        }

        log.info("Acquisizione da file system: presi in carico {} file da questo nodo", presiInCarico);
        return null;
    }

    /**
     * Prova a prendere in carico il file rinominandolo. Ritorna {@code null} se il file
     * non c'e' piu' (altro nodo piu' veloce) o se la rinomina non e' possibile.
     */
    private FdrClaimedFile prendiInCarico(Path file) {
        String nomeOriginale = file.getFileName().toString();
        Path target = file.resolveSibling(nomeOriginale + "." + clusterId + FdrInputProperties.PROCESSING_SUFFIX);
        try {
            Path preso = muovi(file, target);
            log.debug("File {} preso in carico come {}", nomeOriginale, preso.getFileName());
            return new FdrClaimedFile(preso, nomeOriginale);
        } catch (IOException e) {
            log.info("File {} non preso in carico ({}): probabilmente gia' in elaborazione su un altro nodo",
                nomeOriginale, e.getClass().getSimpleName());
            return null;
        }
    }

    private static Path muovi(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // File system che non supporta la move atomica: la presa in carico resta
            // corretta su singolo nodo, ma non garantisce l'esclusione fra nodi diversi.
            log.warn("Move atomica non supportata su {}: la presa in carico non e' protetta fra nodi", source);
            return Files.move(source, target);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) throws ItemStreamException {
        // Nessuno stato da salvare: la presa in carico e' gia' persistita sul file system
    }

    @Override
    public void close() throws ItemStreamException {
        candidati = null;
    }
}
