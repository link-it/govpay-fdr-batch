package it.govpay.fdr.batch.config;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Configurazione di test che fornisce il bean ScheduledJobRunner per il profilo "test".
 * <p>
 * Necessaria perché ScheduledJobRunner ha @Profile("default") e quindi non viene
 * creato automaticamente quando i test usano @ActiveProfiles("test").
 * <p>
 * Nota: non usiamo @Profile("test") qui perché @TestConfiguration è sufficiente
 * e permette di ricevere correttamente i mock di @MockitoBean.
 */
@TestConfiguration
public class TestScheduledJobRunnerConfig {

    @Bean
    public ScheduledJobRunner scheduledJobRunner(
            JobLauncher jobLauncher,
            PreventConcurrentJobLauncher preventConcurrentJobLauncher,
            @Qualifier("fdrAcquisitionJob") Job fdrAcquisitionJob) {
        return new ScheduledJobRunner(jobLauncher, preventConcurrentJobLauncher, fdrAcquisitionJob);
    }
}
