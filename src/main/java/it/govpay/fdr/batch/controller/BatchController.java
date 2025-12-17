package it.govpay.fdr.batch.controller;

import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PreventConcurrentJobLauncher;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller REST per l'esecuzione manuale dei job batch.
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final JobLauncher jobLauncher;
    private final PreventConcurrentJobLauncher preventConcurrentJobLauncher;
    private final Job fdrAcquisitionJob;

    @Value("${govpay.batch.cluster-id:GovPay-FDR-Batch}")
    private String clusterId;

    public BatchController(
            JobLauncher jobLauncher,
            PreventConcurrentJobLauncher preventConcurrentJobLauncher,
            @Qualifier("fdrAcquisitionJob") Job fdrAcquisitionJob) {
        this.jobLauncher = jobLauncher;
        this.preventConcurrentJobLauncher = preventConcurrentJobLauncher;
        this.fdrAcquisitionJob = fdrAcquisitionJob;
    }

    /**
     * Esegue il job FDR Acquisition manualmente in modo asincrono.
     * <p>
     * Il servizio avvia il job e restituisce immediatamente la risposta senza attendere
     * la terminazione del batch. Lo stato del job può essere verificato tramite le
     * tabelle Spring Batch o i log.
     *
     * @param forzaEsecuzione Se true, termina forzatamente l'eventuale esecuzione corrente e avvia una nuova esecuzione
     * @return ResponseEntity con lo stato dell'avvio o Problem in caso di errore
     */
    @GetMapping("/eseguiJob")
    public ResponseEntity<Object> eseguiJob(
            @RequestParam(name = "forzaEsecuzione", required = false, defaultValue = "false") boolean forzaEsecuzione) {
        log.info("Richiesta esecuzione manuale del job {} (forzaEsecuzione={})", Costanti.FDR_ACQUISITION_JOB_NAME, forzaEsecuzione);

        try {
            ResponseEntity<Object> runningJobResponse = gestisciJobInEsecuzione(forzaEsecuzione);
            if (runningJobResponse != null) {
                return runningJobResponse;
            }

            return avviaJobAsincrono();

        } catch (Exception e) {
            log.error("Errore durante l'avvio del job: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Problem.internalServerError("Errore durante l'avvio: " + e.getMessage()));
        }
    }

    /**
     * Gestisce l'eventuale job già in esecuzione.
     */
    private ResponseEntity<Object> gestisciJobInEsecuzione(boolean forzaEsecuzione) {
        JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

        if (currentRunningJobExecution == null) {
            return null;
        }

        if (forzaEsecuzione) {
            return gestisciForzaEsecuzione(currentRunningJobExecution);
        }

        if (this.preventConcurrentJobLauncher.isJobExecutionStale(currentRunningJobExecution)) {
            return gestisciJobStale(currentRunningJobExecution);
        }

        return restituisciJobGiaInEsecuzione(currentRunningJobExecution);
    }

    /**
     * Gestisce la terminazione forzata di un job in esecuzione.
     */
    private ResponseEntity<Object> gestisciForzaEsecuzione(JobExecution currentRunningJobExecution) {
        log.warn("Parametro forzaEsecuzione=true: terminazione forzata di JobExecution {}",
                currentRunningJobExecution.getId());

        if (this.preventConcurrentJobLauncher.forceAbandonJobExecution(currentRunningJobExecution, "Richiesta esecuzione forzata via API REST")) {
            log.info("Job terminato forzatamente con successo. Avvio nuova esecuzione.");
            return null; // Procedi con l'esecuzione
        }

        return ResponseEntity.status(503).body(
                Problem.serviceUnavailable("Impossibile terminare forzatamente il job in esecuzione (JobExecution ID: " + currentRunningJobExecution.getId() + ")"));
    }

    /**
     * Gestisce l'abbandono di un job stale.
     */
    private ResponseEntity<Object> gestisciJobStale(JobExecution currentRunningJobExecution) {
        log.warn("JobExecution {} rilevata come STALE. Procedo con abbandono e riavvio.",
                currentRunningJobExecution.getId());

        if (this.preventConcurrentJobLauncher.abandonStaleJobExecution(currentRunningJobExecution)) {
            log.info("Job stale abbandonato con successo. Avvio nuova esecuzione.");
            return null; // Procedi con l'esecuzione
        }

        return ResponseEntity.status(503).body(
                Problem.serviceUnavailable("Impossibile abbandonare il job stale (JobExecution ID: " + currentRunningJobExecution.getId() + ")"));
    }

    /**
     * Restituisce la risposta quando il job è già in esecuzione.
     */
    private ResponseEntity<Object> restituisciJobGiaInEsecuzione(JobExecution currentRunningJobExecution) {
        String runningClusterId = this.preventConcurrentJobLauncher
                .getClusterIdFromExecution(currentRunningJobExecution);

        String detail = String.format(
                "Il job %s è già in esecuzione (JobExecution ID: %d, Cluster: %s). Usa il parametro forzaEsecuzione=true per terminarlo forzatamente.",
                Costanti.FDR_ACQUISITION_JOB_NAME,
                currentRunningJobExecution.getId(),
                runningClusterId);

        return ResponseEntity.status(409).body(Problem.conflict(detail));
    }

    /**
     * Avvia il job in modo asincrono e restituisce immediatamente la risposta.
     * <p>
     * Il job viene eseguito in un thread separato, permettendo al servizio REST
     * di restituire la risposta senza attendere la terminazione del batch.
     */
    private ResponseEntity<Object> avviaJobAsincrono() {
        JobParameters params = new JobParametersBuilder()
                .addString(Costanti.GOVPAY_BATCH_JOB_ID, Costanti.FDR_ACQUISITION_JOB_NAME)
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN, OffsetDateTime.now().toString())
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, this.clusterId)
                .toJobParameters();

        // Avvia il job in modo asincrono
        CompletableFuture.runAsync(() -> {
            try {
                log.info("Avvio asincrono del job {}", Costanti.FDR_ACQUISITION_JOB_NAME);
                JobExecution execution = jobLauncher.run(fdrAcquisitionJob, params);
                log.info("Job {} terminato con stato: {}", Costanti.FDR_ACQUISITION_JOB_NAME, execution.getStatus());
            } catch (Exception e) {
                log.error("Errore durante l'esecuzione asincrona del job: {}", e.getMessage(), e);
            }
        });

        return ResponseEntity.accepted().build();
    }
}
