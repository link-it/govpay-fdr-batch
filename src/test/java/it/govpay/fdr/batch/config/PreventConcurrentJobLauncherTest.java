package it.govpay.fdr.batch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.test.util.ReflectionTestUtils;

import it.govpay.fdr.batch.Costanti;

class PreventConcurrentJobLauncherTest {

    @Mock
    private JobExplorer jobExplorer;
    @Mock
    private JobRepository jobRepository;

    private PreventConcurrentJobLauncher preventConcurrentJobLauncher;

    private static final String JOB_NAME = Costanti.FDR_ACQUISITION_JOB_NAME;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        preventConcurrentJobLauncher = new PreventConcurrentJobLauncher(jobExplorer, jobRepository);
        // Imposta la soglia di stale a 120 minuti (come da configurazione di default)
        ReflectionTestUtils.setField(preventConcurrentJobLauncher, "staleThresholdMinutes", 120);
    }

    private JobExecution mkExecutionWithCluster(String clusterIdValue) {
        JobInstance jobinstance = new JobInstance(1L, JOB_NAME);

        JobParameters params = new JobParametersBuilder()
            .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, clusterIdValue)
            .toJobParameters();
        return new JobExecution(jobinstance, 1L, params);
    }

    private JobExecution mkExecutionWithClusterAndStatus(String clusterIdValue, BatchStatus status, LocalDateTime lastUpdated) {
        JobInstance jobinstance = new JobInstance(1L, JOB_NAME);

        JobParameters params = new JobParametersBuilder()
            .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, clusterIdValue)
            .toJobParameters();
        JobExecution execution = new JobExecution(jobinstance, 1L, params);
        execution.setStatus(status);
        execution.setStartTime(LocalDateTime.now().minusMinutes(5)); // Set a reasonable start time
        execution.setLastUpdated(lastUpdated);
        return execution;
    }

    private JobExecution mkExecutionWithoutClusterId() {
        JobInstance jobinstance = new JobInstance(1L, JOB_NAME);
        JobParameters params = new JobParametersBuilder().toJobParameters();
        return new JobExecution(jobinstance, 1L, params);
    }

    // ============ Test getCurrentRunningJobExecution ============

    @Test
    void whenJobRunningOnAnotherNode_thenDetected() {
        Set<JobExecution> set = new HashSet<>();
        set.add(mkExecutionWithCluster("OtherNode"));

        when(jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(set);

        JobExecution currentRunningJobExecution = preventConcurrentJobLauncher.getCurrentRunningJobExecution(JOB_NAME);

        assertNotNull(currentRunningJobExecution);
        assertEquals("OtherNode", currentRunningJobExecution.getJobParameters().getString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID));
    }

    @Test
    void whenJobRunningOnSameNode_thenDetected() {
        Set<JobExecution> set = new HashSet<>();
        set.add(mkExecutionWithCluster("GovPay-FDR-Batch"));

        when(jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(set);

        JobExecution currentRunningJobExecution = preventConcurrentJobLauncher.getCurrentRunningJobExecution(JOB_NAME);

        assertNotNull(currentRunningJobExecution);
        assertEquals("GovPay-FDR-Batch", currentRunningJobExecution.getJobParameters().getString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID));
    }

    @Test
    void whenNoJobRunning_thenReturnsNull() {
        when(jobExplorer.findRunningJobExecutions(JOB_NAME)).thenReturn(new HashSet<>());

        JobExecution currentRunningJobExecution = preventConcurrentJobLauncher.getCurrentRunningJobExecution(JOB_NAME);

        assertNull(currentRunningJobExecution);
    }

    // ============ Test isJobExecutionStale ============

    @Test
    void whenJobInUnknownStatus_thenIsStale() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.UNKNOWN, LocalDateTime.now());

        assertTrue(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenJobInAbandonedStatus_thenIsStale() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.ABANDONED, LocalDateTime.now());

        assertTrue(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenJobNotUpdatedForTooLong_thenIsStale() {
        // Job non aggiornato da 125 minuti (oltre la soglia di 120 minuti)
        LocalDateTime lastUpdated = LocalDateTime.now().minusMinutes(125);
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, lastUpdated);

        assertTrue(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenJobUpdatedRecently_thenNotStale() {
        // Job aggiornato 60 minuti fa (entro la soglia di 120 minuti)
        LocalDateTime lastUpdated = LocalDateTime.now().minusMinutes(60);
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, lastUpdated);

        assertFalse(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenJobCompleted_thenNotStale() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.COMPLETED, LocalDateTime.now().minusHours(25));

        assertFalse(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenNullExecution_thenNotStale() {
        assertFalse(preventConcurrentJobLauncher.isJobExecutionStale(null));
    }

    @Test
    void whenJobStartedWithNullLastUpdated_thenNotStale() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, null);
        assertFalse(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    @Test
    void whenJobFailed_thenNotStale() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.FAILED, LocalDateTime.now().minusHours(25));
        assertFalse(preventConcurrentJobLauncher.isJobExecutionStale(execution));
    }

    // ============ Test abandonStaleJobExecution ============

    @Test
    void whenAbandoningStaleJob_thenSuccess() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, LocalDateTime.now().minusMinutes(125));

        boolean result = preventConcurrentJobLauncher.abandonStaleJobExecution(execution);

        assertTrue(result);
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        assertNotNull(execution.getEndTime());
    }

    @Test
    void whenAbandoningStaleJobWithSteps_thenStepsAlsoAbandoned() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, LocalDateTime.now().minusMinutes(125));
        StepExecution stepExecution = new StepExecution("testStep", execution);
        stepExecution.setStatus(BatchStatus.STARTED);
        execution.addStepExecutions(java.util.List.of(stepExecution));

        boolean result = preventConcurrentJobLauncher.abandonStaleJobExecution(execution);

        assertTrue(result);
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        assertEquals(BatchStatus.FAILED, stepExecution.getStatus());
    }

    @Test
    void whenAbandoningStaleJobWithCompletedSteps_thenStepsNotModified() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, LocalDateTime.now().minusMinutes(125));
        StepExecution stepExecution = new StepExecution("testStep", execution);
        stepExecution.setStatus(BatchStatus.COMPLETED);
        execution.addStepExecutions(java.util.List.of(stepExecution));

        boolean result = preventConcurrentJobLauncher.abandonStaleJobExecution(execution);

        assertTrue(result);
        assertEquals(BatchStatus.FAILED, execution.getStatus());
        // Lo step completato non viene modificato
        assertEquals(BatchStatus.COMPLETED, stepExecution.getStatus());
    }

    @Test
    void whenAbandoningNullExecution_thenReturnsFalse() {
        assertFalse(preventConcurrentJobLauncher.abandonStaleJobExecution(null));
    }

    @Test
    void whenAbandoningThrowsException_thenReturnsFalse() {
        JobExecution execution = mkExecutionWithClusterAndStatus("GovPay-FDR-Batch", BatchStatus.STARTED, LocalDateTime.now().minusMinutes(125));

        doThrow(new RuntimeException("Test exception")).when(jobRepository).update(any(JobExecution.class));

        boolean result = preventConcurrentJobLauncher.abandonStaleJobExecution(execution);

        assertFalse(result);
    }

    // ============ Test getClusterIdFromExecution ============

    @Test
    void whenExecutionHasClusterId_thenReturnsIt() {
        JobExecution execution = mkExecutionWithCluster("TestCluster");

        String clusterId = preventConcurrentJobLauncher.getClusterIdFromExecution(execution);

        assertEquals("TestCluster", clusterId);
    }

    @Test
    void whenExecutionHasNoClusterId_thenReturnsNull() {
        JobExecution execution = mkExecutionWithoutClusterId();

        String clusterId = preventConcurrentJobLauncher.getClusterIdFromExecution(execution);

        assertNull(clusterId);
    }

    @Test
    void whenNullExecution_thenReturnsNull() {
        String clusterId = preventConcurrentJobLauncher.getClusterIdFromExecution(null);

        assertNull(clusterId);
    }
}
