package it.govpay.fdr.batch.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import it.govpay.fdr.batch.dto.FdrFileItem;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.service.GdeService;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.FdrCompleteData;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor.PaymentData;

@ExtendWith(MockitoExtension.class)
@DisplayName("FdrFileSystemWriter Tests")
class FdrFileSystemWriterTest {

    private static final Path FILE = Path.of("/tmp/flusso.json.nodo-1.processing");
    private static final String NOME = "flusso.json";

    @Mock
    private FdrFileSystemPersister persister;

    @Mock
    private FdrFileArchiver archiver;

    @Mock
    private GdeService gdeService;

    @Captor
    private ArgumentCaptor<Fr> frCaptor;

    private FdrFileSystemWriter writer;
    private StepExecution stepExecution;

    @BeforeEach
    void setUp() {
        writer = new FdrFileSystemWriter(persister, archiver, gdeService);
        JobInstance jobInstance = new JobInstance(1L, "fdrAcquisitionJob");
        JobExecution jobExecution = new JobExecution(1L, jobInstance, new JobParameters());
        stepExecution = new StepExecution(1L, "fdrFileSystemAcquisitionStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());
        writer.beforeStep(stepExecution);
    }

    private FdrCompleteData datiFlusso() {
        return FdrCompleteData.builder()
            .codDominio("12345678901")
            .codFlusso("FDR-001")
            .codPsp("PSP001")
            .revisione(1L)
            .payments(List.of(PaymentData.builder().iuv("IUV1").build()))
            .build();
    }

    private void scrivi(FdrFileItem item) {
        writer.write(new Chunk<>(List.of(item)));
    }

    private int stat(String chiave) {
        return stepExecution.getExecutionContext().getInt(chiave, -1);
    }

    @Test
    @DisplayName("Un flusso valido viene persistito, archiviato fra i processati e tracciato OK")
    void acquisisce() {
        scrivi(FdrFileItem.acquisire(FILE, NOME, datiFlusso()));

        verify(persister).persisti(any(FdrCompleteData.class));
        verify(archiver).archiviaProcessato(FILE, NOME);
        verify(archiver, never()).archiviaScartato(any(), anyString(), anyString());
        verify(gdeService).saveAcquisizioneFileSystemOk(frCaptor.capture(), any(), any(), eq(NOME),
            eq("Acquisito flusso con 1 rendicontazioni"));

        assertThat(frCaptor.getValue().getCodDominio()).isEqualTo("12345678901");
        assertThat(frCaptor.getValue().getCodFlusso()).isEqualTo("FDR-001");
        assertThat(frCaptor.getValue().getCodPsp()).isEqualTo("PSP001");
        assertThat(frCaptor.getValue().getRevisione()).isEqualTo(1L);
        assertThat(stat(FdrFileSystemWriter.STATS_ACQUISITI)).isEqualTo(1);
        assertThat(stat(FdrFileSystemWriter.STATS_SCARTATI)).isZero();
    }

    @Test
    @DisplayName("Se la persistenza fallisce il file finisce fra gli scarti e l'evento e' KO")
    void persistenzaFallita() {
        doThrow(new IllegalStateException("vincolo violato"))
            .when(persister).persisti(any(FdrCompleteData.class));

        scrivi(FdrFileItem.acquisire(FILE, NOME, datiFlusso()));

        verify(archiver).archiviaScartato(eq(FILE), eq(NOME),
            eq("Acquisizione del flusso fallita: vincolo violato"));
        verify(archiver, never()).archiviaProcessato(any(), anyString());
        verify(gdeService).saveAcquisizioneFileSystemKo(any(), any(), any(), eq(NOME), anyString());
        verify(gdeService, never()).saveAcquisizioneFileSystemOk(any(), any(), any(), anyString(), anyString());

        assertThat(stat(FdrFileSystemWriter.STATS_ACQUISITI)).isZero();
        assertThat(stat(FdrFileSystemWriter.STATS_SCARTATI)).isEqualTo(1);
    }

    @Test
    @DisplayName("Un duplicato viene archiviato fra i processati senza persistere nulla")
    void duplicato() {
        scrivi(FdrFileItem.duplicato(FILE, NOME, "12345678901", "FDR-001", "gia' presente in FR"));

        verify(persister, never()).persisti(any());
        verify(archiver).archiviaProcessato(FILE, NOME);
        verify(gdeService).saveAcquisizioneFileSystemOk(any(), any(), any(), eq(NOME), eq("gia' presente in FR"));

        assertThat(stat(FdrFileSystemWriter.STATS_DUPLICATI)).isEqualTo(1);
        assertThat(stat(FdrFileSystemWriter.STATS_ACQUISITI)).isZero();
    }

    @Test
    @DisplayName("Uno scarto viene archiviato fra gli errori con la motivazione")
    void scartato() {
        scrivi(FdrFileItem.scartato(FILE, NOME, null, null, "Parsing del file fallito"));

        verify(persister, never()).persisti(any());
        verify(archiver).archiviaScartato(FILE, NOME, "Parsing del file fallito");
        verify(gdeService).saveAcquisizioneFileSystemKo(frCaptor.capture(), any(), any(), eq(NOME),
            eq("Parsing del file fallito"));

        // Di un file scartato prima della validazione puo' non essere noto nulla
        assertThat(frCaptor.getValue().getCodDominio()).isNull();
        assertThat(frCaptor.getValue().getCodPsp()).isNull();
        assertThat(stat(FdrFileSystemWriter.STATS_SCARTATI)).isEqualTo(1);
    }

    @Test
    @DisplayName("Un chunk con esiti misti tratta ogni file per conto suo")
    void chunkMisto() {
        writer.write(new Chunk<>(List.of(
            FdrFileItem.acquisire(FILE, "a.json", datiFlusso()),
            FdrFileItem.duplicato(FILE, "b.json", "12345678901", "FDR-002", "gia' presente"),
            FdrFileItem.scartato(FILE, "c.json", null, null, "malformato"))));

        assertThat(stat(FdrFileSystemWriter.STATS_ACQUISITI)).isEqualTo(1);
        assertThat(stat(FdrFileSystemWriter.STATS_DUPLICATI)).isEqualTo(1);
        assertThat(stat(FdrFileSystemWriter.STATS_SCARTATI)).isEqualTo(1);
    }

    @Test
    @DisplayName("Senza StepExecution le statistiche non vengono aggiornate e non si esplode")
    void senzaStepExecution() {
        FdrFileSystemWriter writerSenzaStep = new FdrFileSystemWriter(persister, archiver, gdeService);

        writerSenzaStep.write(new Chunk<>(List.of(FdrFileItem.acquisire(FILE, NOME, datiFlusso()))));

        verify(persister).persisti(any(FdrCompleteData.class));
        verify(archiver).archiviaProcessato(FILE, NOME);
    }

    @Test
    @DisplayName("Un chunk vuoto non produce effetti")
    void chunkVuoto() {
        writer.write(new Chunk<>(List.of()));

        verify(persister, never()).persisti(any());
        verify(gdeService, never()).saveAcquisizioneFileSystemOk(any(), any(), any(), anyString(), anyString());
        verify(gdeService, never()).saveAcquisizioneFileSystemKo(isNull(), any(), any(), anyString(), anyString());
    }
}
