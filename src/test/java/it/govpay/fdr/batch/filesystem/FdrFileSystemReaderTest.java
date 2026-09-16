package it.govpay.fdr.batch.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import it.govpay.fdr.batch.config.FdrInputProperties;
import it.govpay.fdr.batch.dto.FdrClaimedFile;

@DisplayName("FdrFileSystemReader Tests")
class FdrFileSystemReaderTest {

    @TempDir
    Path tempDir;

    private FdrInputProperties properties;

    @BeforeEach
    void setUp() {
        properties = new FdrInputProperties();
        properties.setEnabled(true);
        properties.setDir(tempDir.toString());
    }

    private FdrFileSystemReader reader() {
        FdrFileSystemReader reader = new FdrFileSystemReader(properties, "nodo-1");
        reader.open(new ExecutionContext());
        return reader;
    }

    private List<FdrClaimedFile> leggiTutto(FdrFileSystemReader reader) {
        List<FdrClaimedFile> letti = new ArrayList<>();
        FdrClaimedFile file;
        while ((file = reader.read()) != null) {
            letti.add(file);
        }
        return letti;
    }

    @Test
    @DisplayName("Senza directory configurata lo step e' inerte e non crea nulla")
    void directoryNonConfigurata() {
        properties.setDir(null);

        assertThat(leggiTutto(reader())).isEmpty();
    }

    @Test
    @DisplayName("Una directory configurata ma inesistente non viene creata ne' fa fallire lo step")
    void directoryInesistente() {
        Path inesistente = tempDir.resolve("mai-creata");
        properties.setDir(inesistente.toString());

        assertThat(leggiTutto(reader())).isEmpty();
        assertThat(inesistente).doesNotExist();
    }

    @Test
    @DisplayName("Le directory di archiviazione non vengono create finche' non c'e' un file")
    void nonCreaLeDirectoryDiArchiviazione() {
        reader();

        assertThat(properties.getProcessedDirPath()).doesNotExist();
        assertThat(properties.getErrorDirPath()).doesNotExist();
    }

    @Test
    @DisplayName("I file vengono presi in carico in ordine di nome e rinominati col suffisso di lock")
    void prendeInCaricoInOrdine() throws IOException {
        Files.writeString(tempDir.resolve("b.json"), "{}");
        Files.writeString(tempDir.resolve("a.json"), "{}");

        List<FdrClaimedFile> letti = leggiTutto(reader());

        assertThat(letti).extracting(FdrClaimedFile::nomeOriginale).containsExactly("a.json", "b.json");
        assertThat(letti).allSatisfy(f -> {
            assertThat(f.file().getFileName().toString()).endsWith(".nodo-1.processing");
            assertThat(f.file()).exists();
        });
        assertThat(tempDir.resolve("a.json")).doesNotExist();
    }

    @Test
    @DisplayName("I file con estensione diversa e i file gia' in carico vengono ignorati")
    void ignoraGliAltriFile() throws IOException {
        Files.writeString(tempDir.resolve("flusso.json"), "{}");
        Files.writeString(tempDir.resolve("note.txt"), "non un flusso");
        Files.writeString(tempDir.resolve("vecchio.json.altro-nodo.processing"), "{}");

        List<FdrClaimedFile> letti = leggiTutto(reader());

        assertThat(letti).extracting(FdrClaimedFile::nomeOriginale).containsExactly("flusso.json");
    }

    @Test
    @DisplayName("Un secondo nodo non riprende un file gia' preso in carico")
    void nonRipigliaUnFilePresoInCarico() throws IOException {
        Files.writeString(tempDir.resolve("flusso.json"), "{}");

        List<FdrClaimedFile> primoNodo = leggiTutto(reader());
        FdrFileSystemReader secondoNodo = new FdrFileSystemReader(properties, "nodo-2");
        secondoNodo.open(new ExecutionContext());
        List<FdrClaimedFile> risultatoSecondoNodo = leggiTutto(secondoNodo);

        assertThat(primoNodo).hasSize(1);
        assertThat(risultatoSecondoNodo).isEmpty();
    }

    @Test
    @DisplayName("Il numero di file per esecuzione e' limitato da max-files-per-run")
    void rispettaIlLimitePerEsecuzione() throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(tempDir.resolve("flusso-" + i + ".json"), "{}");
        }
        properties.setMaxFilesPerRun(2);

        List<FdrClaimedFile> letti = leggiTutto(reader());

        assertThat(letti).hasSize(2);
        assertThat(letti).extracting(FdrClaimedFile::nomeOriginale)
            .containsExactly("flusso-0.json", "flusso-1.json");
    }

    @Test
    @DisplayName("Una directory vuota non produce item")
    void directoryVuota() {
        assertThat(leggiTutto(reader())).isEmpty();
    }
}
