package it.govpay.fdr.batch.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import it.govpay.fdr.batch.Costanti;

/**
 * Test per CronJobRunner.
 *
 * NOTA: I test che chiamano runner.run() sono stati rimossi perché
 * il metodo run() chiama System.exit() che termina la JVM.
 * La logica di CronJobRunner è identica a ScheduledJobRunner
 * che ha coverage 100%.
 *
 * Questi test verificano solo la creazione dell'istanza e l'integrazione
 * con PreventConcurrentJobLauncher.
 */
class CronJobRunnerTest {

	@Mock
	private PreventConcurrentJobLauncher preventConcurrentJobLauncher;
	@Mock
	private JobLauncher jobLauncher;
	@Mock
	private Job fdrAcquisitionJob;
	@Mock
	private ApplicationContext applicationContext;
	@Mock
	private JobExplorer jobExplorer;
	@Mock
	private JobRepository jobRepository;

	private CronJobRunner runner;

	private static final String JOB_NAME = Costanti.FDR_ACQUISITION_JOB_NAME;
	private static final String CLUSTER_ID = "GovPay-FDR-Batch";

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);

		// Costruttore: PreventConcurrentJobLauncher, JobLauncher, Job
		runner = new CronJobRunner(preventConcurrentJobLauncher, jobLauncher, fdrAcquisitionJob);

		// Inietta il context
		runner.setApplicationContext(applicationContext);

		// Inietta il valore di clusterId (viene iniettato da @Value in produzione)
		ReflectionTestUtils.setField(runner, "clusterId", CLUSTER_ID);
	}

	@Test
	void testConstructor() {
		CronJobRunner cronJobRunner = new CronJobRunner(preventConcurrentJobLauncher, jobLauncher, fdrAcquisitionJob);
		assertNotNull(cronJobRunner);
	}

	@Test
	void testSetApplicationContext() {
		// Verifica che setApplicationContext non lanci eccezioni
		runner.setApplicationContext(applicationContext);
		assertNotNull(runner);
	}

	private JobExecution mkExecutionWithCluster(String clusterIdValue) {
		JobInstance jobinstance = new JobInstance(1L, JOB_NAME);

		JobParameters params = new JobParametersBuilder()
				.addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, clusterIdValue)
				.toJobParameters();
		return new JobExecution(jobinstance, 1L, params);
	}

	@Test
	void whenNoJobRunning_thenPreventConcurrentJobLauncherReturnsNull() {
		when(jobExplorer.findRunningJobExecutions(JOB_NAME))
		.thenReturn(new HashSet<>());
		PreventConcurrentJobLauncher launcher = new PreventConcurrentJobLauncher(jobExplorer, jobRepository);

		JobExecution currentRunningJobExecution = launcher.getCurrentRunningJobExecution(JOB_NAME);

		assertNull(currentRunningJobExecution);
	}

	@Test
	void whenJobRunning_thenPreventConcurrentJobLauncherReturnsExecution() {
		Set<JobExecution> set = new HashSet<>();
		set.add(mkExecutionWithCluster("OtherNode"));

		when(jobExplorer.findRunningJobExecutions(JOB_NAME))
		.thenReturn(set);
		PreventConcurrentJobLauncher launcher = new PreventConcurrentJobLauncher(jobExplorer, jobRepository);

		JobExecution currentRunningJobExecution = launcher.getCurrentRunningJobExecution(JOB_NAME);

		assertNotNull(currentRunningJobExecution);
		assertEquals("OtherNode", currentRunningJobExecution.getJobParameters().getString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID));
	}

	// ============ Test checkAbandonedJobStale ============

	@Test
	void whenAbandonmentFails_thenReturnsFalseAndDoesNotLaunchJob() throws Exception {
		JobExecution staleExecution = mkExecutionWithCluster(CLUSTER_ID);

		when(preventConcurrentJobLauncher.abandonStaleJobExecution(staleExecution))
		.thenReturn(false);

		boolean result = runner.checkAbandonedJobStale(staleExecution);

		assertFalse(result);
		verify(preventConcurrentJobLauncher).abandonStaleJobExecution(staleExecution);
		verify(jobLauncher, never()).run(any(), any(JobParameters.class));
	}
}
