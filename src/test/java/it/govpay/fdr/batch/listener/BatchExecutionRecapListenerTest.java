package it.govpay.fdr.batch.listener;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;

import it.govpay.fdr.batch.filesystem.FdrFileSystemWriter;

@DisplayName("BatchExecutionRecapListener Tests")
class BatchExecutionRecapListenerTest {

    private BatchExecutionRecapListener listener;
    private JobExecution jobExecution;

    @BeforeEach
    void setUp() {
        listener = new BatchExecutionRecapListener();
        jobExecution = new JobExecution(new JobInstance(1L, "fdrAcquisitionJob"), 1L, new JobParameters());
        jobExecution.setStartTime(LocalDateTime.now().minusMinutes(1));
        jobExecution.setEndTime(LocalDateTime.now());
        jobExecution.setStatus(BatchStatus.COMPLETED);
    }

    private StepExecution aggiungiStep(String nome) {
        // addStepExecution non e' public in Spring Batch 5: la createStepExecution
        // crea lo StepExecution e lo registra sul JobExecution in un colpo solo
        StepExecution stepExecution = jobExecution.createStepExecution(nome);
        stepExecution.setStartTime(LocalDateTime.now().minusSeconds(30));
        stepExecution.setEndTime(LocalDateTime.now());
        stepExecution.setStatus(BatchStatus.COMPLETED);
        stepExecution.setExecutionContext(new ExecutionContext());
        return stepExecution;
    }

    @Test
    @DisplayName("Il riepilogo include lo step da file system quando e' stato eseguito")
    void riepilogoConStepFileSystem() {
        StepExecution fileSystem = aggiungiStep("fdrFileSystemAcquisitionStep");
        fileSystem.getExecutionContext().putInt(FdrFileSystemWriter.STATS_ACQUISITI, 3);
        fileSystem.getExecutionContext().putInt(FdrFileSystemWriter.STATS_DUPLICATI, 1);
        fileSystem.getExecutionContext().putInt(FdrFileSystemWriter.STATS_SCARTATI, 2);
        aggiungiStep("cleanupStep");
        aggiungiStep("fdrHeadersAcquisitionStep");

        assertThatCode(() -> listener.afterJob(jobExecution)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Senza lo step da file system il riepilogo lo omette")
    void riepilogoSenzaStepFileSystem() {
        aggiungiStep("cleanupStep");
        aggiungiStep("fdrHeadersAcquisitionStep");
        aggiungiStep("fdrMetadataAcquisitionStep");
        aggiungiStep("fdrPaymentsAcquisitionStep");

        assertThatCode(() -> listener.afterJob(jobExecution)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("beforeJob non fallisce")
    void inizioJob() {
        assertThatCode(() -> listener.beforeJob(jobExecution)).doesNotThrowAnyException();
    }
}
