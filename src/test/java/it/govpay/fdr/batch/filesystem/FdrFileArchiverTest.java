package it.govpay.fdr.batch.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import it.govpay.fdr.batch.config.FdrInputProperties;

@DisplayName("FdrFileArchiver Tests")
class FdrFileArchiverTest {

    @TempDir
    Path tempDir;

    private FdrInputProperties properties;
    private FdrFileArchiver archiver;

    @BeforeEach
    void setUp() {
        properties = new FdrInputProperties();
        properties.setDir(tempDir.toString());
        archiver = new FdrFileArchiver(properties);
    }

    private Path presoInCarico(String nomeOriginale) throws IOException {
        Path file = tempDir.resolve(nomeOriginale + ".nodo-1.processing");
        Files.writeString(file, "{}");
        return file;
    }

    @Test
    @DisplayName("Il file acquisito finisce in processed col nome originale")
    void archiviaProcessato() throws IOException {
        archiver.archiviaProcessato(presoInCarico("flusso.json"), "flusso.json");

        assertThat(properties.getProcessedDirPath().resolve("flusso.json")).exists();
        assertThat(tempDir.resolve("flusso.json.nodo-1.processing")).doesNotExist();
    }

    @Test
    @DisplayName("Il file scartato finisce in error insieme alla motivazione")
    void archiviaScartato() throws IOException {
        archiver.archiviaScartato(presoInCarico("flusso.json"), "flusso.json", "Dominio non censito");

        Path archiviato = properties.getErrorDirPath().resolve("flusso.json");
        assertThat(archiviato).exists();
        assertThat(archiviato.resolveSibling("flusso.json.error.txt"))
            .exists()
            .content().contains("Dominio non censito");
    }

    @Test
    @DisplayName("Un file omonimo gia' archiviato non viene sovrascritto")
    void nonSovrascriveUnOmonimo() throws IOException {
        Files.createDirectories(properties.getProcessedDirPath());
        Files.writeString(properties.getProcessedDirPath().resolve("flusso.json"), "primo");

        archiver.archiviaProcessato(presoInCarico("flusso.json"), "flusso.json");

        assertThat(properties.getProcessedDirPath().resolve("flusso.json")).hasContent("primo");
        try (var archivio = Files.list(properties.getProcessedDirPath())) {
            List<String> nomi = archivio.map(p -> p.getFileName().toString()).toList();
            assertThat(nomi).hasSize(2);
            assertThat(nomi).anySatisfy(nome ->
                assertThat(nome).startsWith("flusso-").endsWith(".json"));
        }
    }
}
