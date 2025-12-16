package it.govpay.fdr.batch.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PreventConcurrentJobLauncher;

class BatchControllerTest {

    @Mock
    private JobLauncher jobLauncher;

    @Mock
    private PreventConcurrentJobLauncher preventConcurrentJobLauncher;

    @Mock
    private Job fdrAcquisitionJob;

    private BatchController batchController;

    private static final String CLUSTER_ID = "TestCluster";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        batchController = new BatchController(jobLauncher, preventConcurrentJobLauncher, fdrAcquisitionJob);
        ReflectionTestUtils.setField(batchController, "clusterId", CLUSTER_ID);
    }

    private JobExecution createJobExecution(String clusterId, BatchStatus status) {
        JobInstance jobInstance = new JobInstance(1L, Costanti.FDR_ACQUISITION_JOB_NAME);
        JobParameters params = new JobParametersBuilder()
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, clusterId)
                .toJobParameters();
        JobExecution execution = new JobExecution(jobInstance, 1L, params);
        execution.setStatus(status);
        execution.setStartTime(LocalDateTime.now().minusMinutes(5));
        execution.setLastUpdated(LocalDateTime.now());
        return execution;
    }

    // ============ Test avvio normale (nessun job in esecuzione) ============

    @Test
    void whenNoJobRunning_thenReturns202Accepted() throws Exception {
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(null);

        JobExecution mockExecution = createJobExecution(CLUSTER_ID, BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
                .thenReturn(mockExecution);

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());

        // Attendi che il job asincrono venga avviato
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class)));
    }

    @Test
    void whenNoJobRunningAndForzaEsecuzioneTrue_thenReturns202Accepted() throws Exception {
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(null);

        JobExecution mockExecution = createJobExecution(CLUSTER_ID, BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
                .thenReturn(mockExecution);

        ResponseEntity<Object> response = batchController.eseguiJob(true);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNull(response.getBody());
    }

    // ============ Test job già in esecuzione (HTTP 409 Conflict) ============

    @Test
    void whenJobAlreadyRunning_thenReturns409Conflict() {
        JobExecution runningExecution = createJobExecution("OtherCluster", BatchStatus.STARTED);
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(runningExecution);
        when(preventConcurrentJobLauncher.isJobExecutionStale(runningExecution))
                .thenReturn(false);
        when(preventConcurrentJobLauncher.getClusterIdFromExecution(runningExecution))
                .thenReturn("OtherCluster");

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        Problem problem = (Problem) response.getBody();
        assertEquals(409, problem.getStatus());
        assertEquals("Conflitto", problem.getTitle());
    }

    // ============ Test job stale (abbandono automatico) ============

    @Test
    void whenJobIsStale_thenAbandonAndReturns202Accepted() throws Exception {
        JobExecution staleExecution = createJobExecution("StaleCluster", BatchStatus.STARTED);
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(staleExecution);
        when(preventConcurrentJobLauncher.isJobExecutionStale(staleExecution))
                .thenReturn(true);
        when(preventConcurrentJobLauncher.abandonStaleJobExecution(staleExecution))
                .thenReturn(true);

        JobExecution mockExecution = createJobExecution(CLUSTER_ID, BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
                .thenReturn(mockExecution);

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(preventConcurrentJobLauncher).abandonStaleJobExecution(staleExecution);
    }

    @Test
    void whenJobIsStaleButAbandonFails_thenReturns503ServiceUnavailable() {
        JobExecution staleExecution = createJobExecution("StaleCluster", BatchStatus.STARTED);
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(staleExecution);
        when(preventConcurrentJobLauncher.isJobExecutionStale(staleExecution))
                .thenReturn(true);
        when(preventConcurrentJobLauncher.abandonStaleJobExecution(staleExecution))
                .thenReturn(false);

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        Problem problem = (Problem) response.getBody();
        assertEquals(503, problem.getStatus());
    }

    // ============ Test forzaEsecuzione=true ============

    @Test
    void whenForzaEsecuzioneAndJobRunning_thenForceAbandonAndReturns202Accepted() throws Exception {
        JobExecution runningExecution = createJobExecution("OtherCluster", BatchStatus.STARTED);
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(runningExecution);
        when(preventConcurrentJobLauncher.forceAbandonJobExecution(eq(runningExecution), anyString()))
                .thenReturn(true);

        JobExecution mockExecution = createJobExecution(CLUSTER_ID, BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
                .thenReturn(mockExecution);

        ResponseEntity<Object> response = batchController.eseguiJob(true);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        verify(preventConcurrentJobLauncher).forceAbandonJobExecution(eq(runningExecution), anyString());
        // Non deve verificare se è stale quando forza l'esecuzione
        verify(preventConcurrentJobLauncher, never()).isJobExecutionStale(any());
    }

    @Test
    void whenForzaEsecuzioneButForceAbandonFails_thenReturns503ServiceUnavailable() {
        JobExecution runningExecution = createJobExecution("OtherCluster", BatchStatus.STARTED);
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(runningExecution);
        when(preventConcurrentJobLauncher.forceAbandonJobExecution(eq(runningExecution), anyString()))
                .thenReturn(false);

        ResponseEntity<Object> response = batchController.eseguiJob(true);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getBody());
        Problem problem = (Problem) response.getBody();
        assertEquals(503, problem.getStatus());
    }

    // ============ Test errore durante l'avvio (HTTP 500) ============

    @Test
    void whenExceptionDuringJobCheck_thenReturns500InternalServerError() {
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenThrow(new RuntimeException("Database connection error"));

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        Problem problem = (Problem) response.getBody();
        assertEquals(500, problem.getStatus());
        assertEquals("Errore interno del server", problem.getTitle());
    }

    // ============ Test errore asincrono durante l'esecuzione del job ============

    @Test
    void whenJobLauncherThrowsExceptionAsync_thenReturns202ButLogsError() throws Exception {
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(null);

        // Il job launcher lancia un'eccezione (simulando un errore durante l'esecuzione)
        doThrow(new RuntimeException("Job execution failed"))
                .when(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class));

        // La risposta deve comunque essere 202 perché l'esecuzione è asincrona
        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        // Attendi che il CompletableFuture abbia provato ad eseguire il job
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class)));
    }

    // ============ Test verifica parametri job ============

    @Test
    void whenJobStarted_thenCorrectParametersArePassed() throws Exception {
        when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME))
                .thenReturn(null);

        JobExecution mockExecution = createJobExecution(CLUSTER_ID, BatchStatus.COMPLETED);
        when(jobLauncher.run(eq(fdrAcquisitionJob), any(JobParameters.class)))
                .thenAnswer(invocation -> {
                    JobParameters params = invocation.getArgument(1);
                    assertEquals(Costanti.FDR_ACQUISITION_JOB_NAME, params.getString(Costanti.GOVPAY_BATCH_JOB_ID));
                    assertEquals(CLUSTER_ID, params.getString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID));
                    assertNotNull(params.getString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN));
                    return mockExecution;
                });

        ResponseEntity<Object> response = batchController.eseguiJob(false);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());

        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(jobLauncher).run(eq(fdrAcquisitionJob), any(JobParameters.class)));
    }
}
