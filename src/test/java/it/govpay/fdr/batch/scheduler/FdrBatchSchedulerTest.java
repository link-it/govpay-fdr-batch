package it.govpay.fdr.batch.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;

import it.govpay.fdr.batch.config.BatchProperties;

/**
 * Test per FdrBatchScheduler
 */
@DisplayName("FdrBatchScheduler Tests")
class FdrBatchSchedulerTest {

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private Job fdrAcquisitionJob;

    @Mock
    private BatchProperties batchProperties;

    @Mock
    private JobExecution jobExecution;

    private FdrBatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new FdrBatchScheduler(jobLauncher, fdrAcquisitionJob, batchProperties);
    }

    @Test
    @DisplayName("Test constructor creates instance")
    void testConstructor() {
        FdrBatchScheduler newScheduler = new FdrBatchScheduler(jobLauncher, fdrAcquisitionJob, batchProperties);
        assertNotNull(newScheduler);
    }

    @Test
    @DisplayName("Test successful job execution")
    void testRunFdrAcquisitionJobSuccess() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenReturn(jobExecution);

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNotNull(result);
        assertEquals(jobExecution, result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test job execution with JobExecutionAlreadyRunningException")
    void testRunFdrAcquisitionJobAlreadyRunning() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new JobExecutionAlreadyRunningException("Job is already running"));

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNull(result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test job execution with JobRestartException")
    void testRunFdrAcquisitionJobRestartException() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new JobRestartException("Cannot restart job"));

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNull(result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test job execution with JobInstanceAlreadyCompleteException")
    void testRunFdrAcquisitionJobInstanceAlreadyComplete() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new JobInstanceAlreadyCompleteException("Job instance already complete"));

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNull(result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test job execution with JobParametersInvalidException")
    void testRunFdrAcquisitionJobInvalidParameters() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new JobParametersInvalidException("Invalid job parameters"));

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNull(result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test job execution with generic exception")
    void testRunFdrAcquisitionJobGenericException() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new RuntimeException("Unexpected error"));

        JobExecution result = scheduler.runFdrAcquisitionJob();

        assertNull(result);
        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test manual trigger calls runFdrAcquisitionJob")
    void testTriggerManually() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenReturn(jobExecution);

        scheduler.triggerManually();

        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test manual trigger with exception")
    void testTriggerManuallyWithException() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenThrow(new RuntimeException("Error during manual trigger"));

        // Should not throw exception, just log it
        assertDoesNotThrow(() -> scheduler.triggerManually());

        verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }

    @Test
    @DisplayName("Test multiple successful executions")
    void testMultipleSuccessfulExecutions() throws Exception {
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
            .thenReturn(jobExecution);

        JobExecution result1 = scheduler.runFdrAcquisitionJob();
        JobExecution result2 = scheduler.runFdrAcquisitionJob();

        assertNotNull(result1);
        assertNotNull(result2);
        verify(jobLauncher, times(2)).run(eq(fdrAcquisitionJob), any(JobParameters.class));
    }
}
