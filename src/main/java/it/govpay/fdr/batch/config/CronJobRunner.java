package it.govpay.fdr.batch.config;

import java.time.OffsetDateTime;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.Costanti;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Runner per l'esecuzione da command line del job FDR Acquisition in modalità multi-nodo.
 * <p>
 * Questa classe gestisce l'esecuzione singola del batch quando lanciato via CLI,
 * implementando la logica di coordinamento tra nodi diversi per evitare esecuzioni
 * concorrenti dello stesso job.
 * <p>
 * Funzionamento:
 * - Esegue il job una sola volta al startup (CommandLineRunner)
 * - Prima di avviare il job, verifica se è già in esecuzione (su qualsiasi nodo)
 * - Se è in esecuzione su un altro nodo (clusterId diverso), esce senza avviarlo
 * - Se è in esecuzione sullo stesso nodo, logga un warning (job bloccato)
 * - Se non è in esecuzione, avvia il job passando il clusterId come parametro
 * - Al termine del job, termina l'applicazione
 * <p>
 * Attivo solo con profile "cron" (non "default").
 * <p>
 * Tipico utilizzo: esecuzione via cron di sistema o scheduler esterno.
 */
@Slf4j
@Component
@Profile("cron")
@RequiredArgsConstructor
public class CronJobRunner implements CommandLineRunner, ApplicationContextAware {

    private ApplicationContext context;

    private final PreventConcurrentJobLauncher preventConcurrentJobLauncher;
    private final JobLauncher jobLauncher;
    @Qualifier("fdrAcquisitionJob")
    private final Job fdrAcquisitionJob;

    @Value("${govpay.batch.cluster-id:GovPay-FDR-Batch}")
    private String clusterId;

    /**
     * Esegue il job FDR Acquisition con i parametri necessari per la gestione multi-nodo.
     *
     * @throws JobExecutionAlreadyRunningException se il job è già in esecuzione
     * @throws JobRestartException se il job non può essere riavviato
     * @throws JobInstanceAlreadyCompleteException se l'istanza del job è già completata
     * @throws JobParametersInvalidException se i parametri del job non sono validi
     */
    private void runFdrAcquisitionJob() throws JobExecutionAlreadyRunningException, JobRestartException,
            JobInstanceAlreadyCompleteException, JobParametersInvalidException {
        JobParameters params = new JobParametersBuilder()
                .addString(Costanti.GOVPAY_BATCH_JOB_ID, Costanti.FDR_ACQUISITION_JOB_NAME)
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN, OffsetDateTime.now().toString())
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, this.clusterId)
                .toJobParameters();

        jobLauncher.run(fdrAcquisitionJob, params);
    }

    /**
     * Metodo eseguito al startup dell'applicazione per avviare il batch FDR Acquisition.
     * <p>
     * Prima di avviare il job, verifica se è già in esecuzione su questo nodo o su altri nodi.
     * Al termine dell'esecuzione, termina l'applicazione con exit code 0.
     *
     * @param args argomenti della command line
     * @throws Exception se si verifica un errore durante l'esecuzione
     */
    @Override
    public void run(String... args) throws Exception {
        log.info("Avvio {} da command line", Costanti.FDR_ACQUISITION_JOB_NAME);

        JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

        if (currentRunningJobExecution != null) {
            // Verifica se il job è stale (bloccato o in stato anomalo)
            if (this.preventConcurrentJobLauncher.isJobExecutionStale(currentRunningJobExecution)) {
                log.warn("JobExecution {} rilevata come STALE. Procedo con abbandono e riavvio.",
                    currentRunningJobExecution.getId());

                boolean abandoned = checkAbandonedJobStale(currentRunningJobExecution);

                // Terminazione dell'applicazione
                int exitCode = SpringApplication.exit(context, () -> abandoned ? 0 : 1);
                System.exit(exitCode);
                return;
            }
            
            // Job in esecuzione normale - estrai il clusterid dell'esecuzione corrente
            String runningClusterId = this.preventConcurrentJobLauncher.getClusterIdFromExecution(currentRunningJobExecution);

            if (runningClusterId != null && !runningClusterId.equals(this.clusterId)) {
                log.info("Il job {} è in esecuzione su un altro nodo ({}). Uscita.",
                    Costanti.FDR_ACQUISITION_JOB_NAME, runningClusterId);
            } else {
                log.warn("Il job {} è ancora in esecuzione sul nodo corrente ({}). Uscita.",
                    Costanti.FDR_ACQUISITION_JOB_NAME, runningClusterId);
            }
            // Terminazione dell'applicazione
            int exitCode = SpringApplication.exit(context, () -> 0);
            System.exit(exitCode);
        }

        runFdrAcquisitionJob();
        log.info("{} completato.", Costanti.FDR_ACQUISITION_JOB_NAME);

        // Terminazione dell'applicazione
        int exitCode = SpringApplication.exit(context, () -> 0);
        System.exit(exitCode);
    }

	public boolean checkAbandonedJobStale(JobExecution currentRunningJobExecution) throws JobExecutionAlreadyRunningException,
			JobRestartException, JobInstanceAlreadyCompleteException, JobParametersInvalidException {
		// Abbandona il job stale
		boolean abandoned = this.preventConcurrentJobLauncher.abandonStaleJobExecution(currentRunningJobExecution);

		if (abandoned) {
		    log.info("Job stale abbandonato con successo. Avvio nuova esecuzione.");
		    // Procedi con l'avvio di una nuova esecuzione
		    runFdrAcquisitionJob();
		    log.info("{} completato.", Costanti.FDR_ACQUISITION_JOB_NAME);
		} else {
		    log.error("Impossibile abbandonare il job stale. Uscita senza avviare nuova esecuzione.");
		}
		return abandoned;
	}

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }
}
