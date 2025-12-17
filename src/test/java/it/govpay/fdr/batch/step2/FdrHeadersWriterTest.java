package it.govpay.fdr.batch.step2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;

import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;

/**
 * Unit tests for FdrHeadersWriter
 */
@ExtendWith(MockitoExtension.class)
class FdrHeadersWriterTest {

    @Mock
    private FrTempRepository frTempRepository;

    @Mock
    private FrRepository frRepository;

    @Captor
    private ArgumentCaptor<FrTemp> frTempCaptor;

    private FdrHeadersWriter writer;

    @BeforeEach
    void setUp() {
        writer = new FdrHeadersWriter(frTempRepository, frRepository);
    }

    @Test
    @DisplayName("Should write all headers to FR_TEMP")
    void testWriteMultipleHeaders() throws Exception {
        // Given: Batch with 3 headers (all new)
        String codDominio = "12345678901";
        List<FdrHeadersBatch.FdrHeader> headers = new ArrayList<>();
        headers.add(createHeader("FDR-001", "PSP001", 1L));
        headers.add(createHeader("FDR-002", "PSP001", 1L));
        headers.add(createHeader("FDR-003", "PSP002", 1L));

        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(headers)
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch));

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // When: Write
        writer.write(chunk);

        // Then: Should save all 3 headers
        verify(frTempRepository, times(3)).save(any(FrTemp.class));
        verify(frRepository, times(3)).existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any());
        verify(frTempRepository, times(3)).existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should skip duplicate headers")
    void testWriteSkipsDuplicates() throws Exception {
        // Given: Batch with 3 headers (2 new, 1 duplicate)
        String codDominio = "12345678901";
        List<FdrHeadersBatch.FdrHeader> headers = new ArrayList<>();
        headers.add(createHeader("FDR-001", "PSP001", 1L));
        headers.add(createHeader("FDR-002", "PSP001", 1L)); // Duplicate
        headers.add(createHeader("FDR-003", "PSP002", 1L));

        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(headers)
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch));

        // None already in FR
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // FDR-002 already exists in FR_TEMP
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-001"), eq("PSP001"), eq(1L))).thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-002"), eq("PSP001"), eq(1L))).thenReturn(true); // Duplicate
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-003"), eq("PSP002"), eq(1L))).thenReturn(false);

        // When: Write
        writer.write(chunk);

        // Then: Should save only 2 headers (skip duplicate in FR_TEMP)
        verify(frTempRepository, times(2)).save(any(FrTemp.class));
    }

    @Test
    @DisplayName("Should write multiple batches in single chunk")
    void testWriteMultipleBatches() throws Exception {
        // Given: Chunk with 2 batches
        FdrHeadersBatch batch1 = FdrHeadersBatch.builder()
            .codDominio("12345678901")
            .headers(List.of(createHeader("FDR-001", "PSP001", 1L)))
            .build();

        FdrHeadersBatch batch2 = FdrHeadersBatch.builder()
            .codDominio("12345678902")
            .headers(List.of(createHeader("FDR-002", "PSP002", 1L)))
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch1, batch2));

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // When: Write
        writer.write(chunk);

        // Then: Should save both batches
        verify(frTempRepository, times(2)).save(any(FrTemp.class));
    }

    @Test
    @DisplayName("Should correctly map header fields to FrTemp entity")
    void testFieldMapping() throws Exception {
        // Given: Single header with all fields
        String codDominio = "12345678901";
        String codFlusso = "FDR-001";
        String idPsp = "PSP001";
        Long revisione = 1L;
        Instant dataOraFlusso = Instant.parse("2025-01-27T10:30:00Z");
        Instant dataOraPubblicazione = Instant.parse("2025-01-27T11:00:00Z");

        FdrHeadersBatch.FdrHeader header = FdrHeadersBatch.FdrHeader.builder()
            .codFlusso(codFlusso)
            .idPsp(idPsp)
            .revision(revisione)
            .dataOraFlusso(dataOraFlusso)
            .dataOraPubblicazione(dataOraPubblicazione)
            .build();

        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(List.of(header))
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch));

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // When: Write
        writer.write(chunk);

        // Then: Verify field mapping
        verify(frTempRepository).save(frTempCaptor.capture());
        FrTemp saved = frTempCaptor.getValue();

        assertThat(saved.getCodDominio()).isEqualTo(codDominio);
        assertThat(saved.getCodFlusso()).isEqualTo(codFlusso);
        assertThat(saved.getIdPsp()).isEqualTo(idPsp);
        assertThat(saved.getRevisione()).isEqualTo(revisione);
        assertThat(saved.getDataOraFlusso()).isEqualTo(dataOraFlusso);
        assertThat(saved.getDataOraPubblicazione()).isEqualTo(dataOraPubblicazione);
    }

    @Test
    @DisplayName("Should handle empty chunk gracefully")
    void testWriteEmptyChunk() throws Exception {
        // Given: Empty chunk
        Chunk<FdrHeadersBatch> chunk = new Chunk<>();

        // When: Write
        writer.write(chunk);

        // Then: Should not save anything
        verify(frTempRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should handle batch with empty headers list")
    void testWriteBatchWithEmptyHeaders() throws Exception {
        // Given: Batch with empty headers list
        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio("12345678901")
            .headers(new ArrayList<>())
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch));

        // When: Write
        writer.write(chunk);

        // Then: Should not save anything
        verify(frTempRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should skip headers already present in FR table")
    void testWriteSkipsHeadersAlreadyInFr() throws Exception {
        // Given: Batch with 3 headers (1 new, 1 already in FR, 1 already in FR_TEMP)
        String codDominio = "12345678901";
        List<FdrHeadersBatch.FdrHeader> headers = new ArrayList<>();
        headers.add(createHeader("FDR-001", "PSP001", 1L)); // New
        headers.add(createHeader("FDR-002", "PSP001", 1L)); // Already in FR
        headers.add(createHeader("FDR-003", "PSP002", 1L)); // Already in FR_TEMP

        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(headers)
            .build();

        Chunk<FdrHeadersBatch> chunk = new Chunk<>(List.of(batch));

        // FDR-002 already in FR (should be skipped, no FR_TEMP check needed)
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            eq(codDominio), eq("FDR-001"), eq("PSP001"), eq(1L))).thenReturn(false);
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            eq(codDominio), eq("FDR-002"), eq("PSP001"), eq(1L))).thenReturn(true); // Already in FR
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(
            eq(codDominio), eq("FDR-003"), eq("PSP002"), eq(1L))).thenReturn(false);

        // FDR-003 already in FR_TEMP
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-001"), eq("PSP001"), eq(1L))).thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-003"), eq("PSP002"), eq(1L))).thenReturn(true);

        // When: Write
        writer.write(chunk);

        // Then: Should save only 1 header (FDR-001)
        verify(frTempRepository, times(1)).save(any(FrTemp.class));

        // Verify FR was checked for all 3 headers (with codDominio)
        verify(frRepository, times(3)).existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any());

        // Verify FR_TEMP was checked only for headers not in FR (FDR-001, FDR-003)
        verify(frTempRepository, times(2)).existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any());

        // Verify FDR-002 was never checked in FR_TEMP (skipped because already in FR)
        verify(frTempRepository, never()).existsByCodDominioAndCodFlussoAndIdPspAndRevisione(
            eq(codDominio), eq("FDR-002"), eq("PSP001"), eq(1L));
    }

    private FdrHeadersBatch.FdrHeader createHeader(String codFlusso, String idPsp, Long revision) {
        return FdrHeadersBatch.FdrHeader.builder()
            .codFlusso(codFlusso)
            .idPsp(idPsp)
            .revision(revision)
            .dataOraFlusso(Instant.now())
            .dataOraPubblicazione(Instant.now())
            .build();
    }

    // ============ Tests for beforeStep and statistics tracking ============

    @Test
    @DisplayName("beforeStep should initialize counters in execution context")
    void testBeforeStepInitializesCounters() {
        // Given
        JobInstance jobInstance = new JobInstance(1L, "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());

        // When
        writer.beforeStep(stepExecution);

        // Then
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SAVED_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_TEMP_COUNT)).isZero();
    }

    @Test
    @DisplayName("Should update statistics correctly when saving new headers")
    void testStatisticsForNewHeaders() throws Exception {
        // Given
        JobInstance jobInstance = new JobInstance(1L, "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());
        writer.beforeStep(stepExecution);

        String codDominio = "12345678901";
        List<FdrHeadersBatch.FdrHeader> headers = List.of(
            createHeader("FDR-001", "PSP001", 1L),
            createHeader("FDR-002", "PSP001", 1L)
        );
        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(headers)
            .build();

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // When
        writer.write(new Chunk<>(List.of(batch)));

        // Then
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SAVED_COUNT)).isEqualTo(2);
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_TEMP_COUNT)).isZero();
    }

    @Test
    @DisplayName("Should update statistics correctly when skipping headers in FR")
    void testStatisticsForSkippedInFr() throws Exception {
        // Given
        JobInstance jobInstance = new JobInstance(1L, "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());
        writer.beforeStep(stepExecution);

        String codDominio = "12345678901";
        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(List.of(createHeader("FDR-001", "PSP001", 1L)))
            .build();

        // Already exists in FR
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(true);

        // When
        writer.write(new Chunk<>(List.of(batch)));

        // Then
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SAVED_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_COUNT)).isEqualTo(1);
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_TEMP_COUNT)).isZero();
    }

    @Test
    @DisplayName("Should update statistics correctly when skipping headers in FR_TEMP")
    void testStatisticsForSkippedInFrTemp() throws Exception {
        // Given
        JobInstance jobInstance = new JobInstance(1L, "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());
        writer.beforeStep(stepExecution);

        String codDominio = "12345678901";
        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(List.of(createHeader("FDR-001", "PSP001", 1L)))
            .build();

        // Not in FR, but already in FR_TEMP
        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(true);

        // When
        writer.write(new Chunk<>(List.of(batch)));

        // Then
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SAVED_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_COUNT)).isZero();
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SKIPPED_FR_TEMP_COUNT)).isEqualTo(1);
    }

    @Test
    @DisplayName("Should accumulate statistics across multiple writes")
    void testStatisticsAccumulation() throws Exception {
        // Given
        JobInstance jobInstance = new JobInstance(1L, "testJob");
        JobExecution jobExecution = new JobExecution(jobInstance, 1L, new JobParameters());
        StepExecution stepExecution = new StepExecution("testStep", jobExecution);
        stepExecution.setExecutionContext(new ExecutionContext());
        writer.beforeStep(stepExecution);

        String codDominio = "12345678901";

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        // When - write multiple chunks
        FdrHeadersBatch batch1 = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(List.of(createHeader("FDR-001", "PSP001", 1L)))
            .build();
        writer.write(new Chunk<>(List.of(batch1)));

        FdrHeadersBatch batch2 = FdrHeadersBatch.builder()
            .codDominio(codDominio)
            .headers(List.of(createHeader("FDR-002", "PSP001", 1L)))
            .build();
        writer.write(new Chunk<>(List.of(batch2)));

        // Then
        assertThat(stepExecution.getExecutionContext().getInt(FdrHeadersWriter.STATS_SAVED_COUNT)).isEqualTo(2);
    }

    @Test
    @DisplayName("Should handle write without beforeStep being called")
    void testWriteWithoutBeforeStep() throws Exception {
        // Given - writer without beforeStep called
        FdrHeadersWriter writerNoStep = new FdrHeadersWriter(frTempRepository, frRepository);

        when(frRepository.existsByCodDominioAndCodFlussoAndCodPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);
        when(frTempRepository.existsByCodDominioAndCodFlussoAndIdPspAndRevisione(any(), any(), any(), any()))
            .thenReturn(false);

        FdrHeadersBatch batch = FdrHeadersBatch.builder()
            .codDominio("12345678901")
            .headers(List.of(createHeader("FDR-001", "PSP001", 1L)))
            .build();

        // When & Then - should not throw
        writerNoStep.write(new Chunk<>(List.of(batch)));
        verify(frTempRepository, times(1)).save(any(FrTemp.class));
    }
}
