package it.govpay.fdr.batch.listener;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import it.govpay.fdr.batch.filesystem.FdrFileSystemWriter;

@DisplayName("BatchExecutionRecapListener Tests")
class BatchExecutionRecapListenerTest {

    private BatchExecutionRecapListener listener;
    private JobExecution jobExecution;

    @BeforeEach
    void setUp() {
        listener = new BatchExecutionRecapListener();
        jobExecution = new JobExecution(1L, new JobInstance(1L, "fdrAcquisitionJob"), new JobParameters());
        jobExecution.setStartTime(LocalDateTime.now().minusMinutes(1));
        jobExecution.setEndTime(LocalDateTime.now());
        jobExecution.setStatus(BatchStatus.COMPLETED);
    }

    private StepExecution aggiungiStep(String nome) {
        StepExecution stepExecution = new StepExecution(nome, jobExecution);
        jobExecution.addStepExecution(stepExecution);
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
