package it.govpay.fdr.batch.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.batch.core.Job;

import it.govpay.common.batch.runner.JobExecutionHelper;

/**
 * Test per CronJobRunner.
 * <p>
 * NOTA: I test che chiamano runner.run() sono stati rimossi perché
 * il metodo run() chiama System.exit() che termina la JVM.
 * La logica di AbstractCronJobRunner è testata nella libreria govpay-common.
 * <p>
 * Questi test verificano solo la creazione dell'istanza.
 */
class CronJobRunnerTest {

    @Mock
    private JobExecutionHelper jobExecutionHelper;
    @Mock
    private Job fdrAcquisitionJob;

    private CronJobRunner runner;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        runner = new CronJobRunner(jobExecutionHelper, fdrAcquisitionJob);
    }

    @Test
    void testConstructor() {
        CronJobRunner cronJobRunner = new CronJobRunner(jobExecutionHelper, fdrAcquisitionJob);
        assertNotNull(cronJobRunner);
    }

    @Test
    void testSetApplicationContext() {
        runner.setApplicationContext(null);
        assertNotNull(runner);
    }
}
