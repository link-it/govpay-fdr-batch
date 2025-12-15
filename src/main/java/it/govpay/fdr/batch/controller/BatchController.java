package it.govpay.fdr.batch.controller;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.batch.core.BatchStatus;
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

    private static final String RESPONSE_STATUS = "status";
    private static final String RESPONSE_MESSAGE = "message";
    private static final String STATUS_ERROR = "ERROR";

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
     * Esegue il job FDR Acquisition manualmente.
     *
     * @param forzaEsecuzione Se true, termina forzatamente l'eventuale esecuzione corrente e avvia una nuova esecuzione
     * @return ResponseEntity con lo stato dell'esecuzione
     */
    @GetMapping("/eseguiJob")
    public ResponseEntity<Map<String, Object>> eseguiJob(
            @RequestParam(name = "forzaEsecuzione", required = false, defaultValue = "false") boolean forzaEsecuzione) {
        log.info("Richiesta esecuzione manuale del job {} (forzaEsecuzione={})", Costanti.FDR_ACQUISITION_JOB_NAME, forzaEsecuzione);

        Map<String, Object> response = new HashMap<>();
        response.put("jobName", Costanti.FDR_ACQUISITION_JOB_NAME);
        response.put("timestamp", OffsetDateTime.now().toString());
        response.put("forzaEsecuzione", forzaEsecuzione);

        try {
            ResponseEntity<Map<String, Object>> runningJobResponse = gestisciJobInEsecuzione(forzaEsecuzione, response);
            if (runningJobResponse != null) {
                return runningJobResponse;
            }

            return eseguiERestituisciRisultato(response);

        } catch (Exception e) {
            log.error("Errore durante l'esecuzione del job: {}", e.getMessage(), e);
            response.put(RESPONSE_STATUS, STATUS_ERROR);
            response.put(RESPONSE_MESSAGE, "Errore durante l'esecuzione: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Gestisce l'eventuale job già in esecuzione.
     *
     * @param forzaEsecuzione Se true, termina forzatamente l'esecuzione corrente
     * @param response La mappa di risposta da popolare
     * @return ResponseEntity se il job non può essere avviato, null se si può procedere
     */
    private ResponseEntity<Map<String, Object>> gestisciJobInEsecuzione(boolean forzaEsecuzione, Map<String, Object> response) {
        JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

        if (currentRunningJobExecution == null) {
            return null;
        }

        if (forzaEsecuzione) {
            return gestisciForzaEsecuzione(currentRunningJobExecution, response);
        }

        if (this.preventConcurrentJobLauncher.isJobExecutionStale(currentRunningJobExecution)) {
            return gestisciJobStale(currentRunningJobExecution, response);
        }

        return restituisciJobGiaInEsecuzione(currentRunningJobExecution, response);
    }

    /**
     * Gestisce la terminazione forzata di un job in esecuzione.
     */
    private ResponseEntity<Map<String, Object>> gestisciForzaEsecuzione(JobExecution currentRunningJobExecution, Map<String, Object> response) {
        log.warn("Parametro forzaEsecuzione=true: terminazione forzata di JobExecution {}",
                currentRunningJobExecution.getId());

        if (this.preventConcurrentJobLauncher.forceAbandonJobExecution(
                currentRunningJobExecution, "Richiesta esecuzione forzata via API REST")) {
            log.info("Job terminato forzatamente con successo. Avvio nuova esecuzione.");
            response.put("previousJobId", currentRunningJobExecution.getId());
            response.put("previousJobTerminated", true);
            return null; // Procedi con l'esecuzione
        }

        response.put(RESPONSE_STATUS, STATUS_ERROR);
        response.put(RESPONSE_MESSAGE, "Impossibile terminare forzatamente il job in esecuzione");
        response.put("runningJobId", currentRunningJobExecution.getId());
        return ResponseEntity.status(503).body(response);
    }

    /**
     * Gestisce l'abbandono di un job stale.
     */
    private ResponseEntity<Map<String, Object>> gestisciJobStale(JobExecution currentRunningJobExecution, Map<String, Object> response) {
        log.warn("JobExecution {} rilevata come STALE. Procedo con abbandono e riavvio.",
                currentRunningJobExecution.getId());

        if (this.preventConcurrentJobLauncher.abandonStaleJobExecution(currentRunningJobExecution)) {
            log.info("Job stale abbandonato con successo. Avvio nuova esecuzione.");
            return null; // Procedi con l'esecuzione
        }

        response.put(RESPONSE_STATUS, STATUS_ERROR);
        response.put(RESPONSE_MESSAGE, "Impossibile abbandonare il job stale");
        response.put("staleJobId", currentRunningJobExecution.getId());
        return ResponseEntity.status(503).body(response);
    }

    /**
     * Restituisce la risposta quando il job è già in esecuzione.
     */
    private ResponseEntity<Map<String, Object>> restituisciJobGiaInEsecuzione(JobExecution currentRunningJobExecution, Map<String, Object> response) {
        String runningClusterId = this.preventConcurrentJobLauncher
                .getClusterIdFromExecution(currentRunningJobExecution);
        response.put(RESPONSE_STATUS, "ALREADY_RUNNING");
        response.put(RESPONSE_MESSAGE, "Il job è già in esecuzione. Usa il parametro forzaEsecuzione=true per terminarlo forzatamente.");
        response.put("runningJobId", currentRunningJobExecution.getId());
        response.put("runningOnCluster", runningClusterId);
        return ResponseEntity.status(409).body(response);
    }

    /**
     * Esegue il job e restituisce il risultato.
     */
    private ResponseEntity<Map<String, Object>> eseguiERestituisciRisultato(Map<String, Object> response) throws Exception {
        JobParameters params = new JobParametersBuilder()
                .addString(Costanti.GOVPAY_BATCH_JOB_ID, Costanti.FDR_ACQUISITION_JOB_NAME)
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN, OffsetDateTime.now().toString())
                .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, this.clusterId)
                .toJobParameters();

        JobExecution execution = jobLauncher.run(fdrAcquisitionJob, params);

        response.put(RESPONSE_STATUS, execution.getStatus().toString());
        response.put("jobExecutionId", execution.getId());
        response.put("exitCode", execution.getExitStatus().getExitCode());

        return costruisciRispostaEsecuzione(execution, response);
    }

    /**
     * Costruisce la risposta in base allo stato dell'esecuzione.
     */
    private ResponseEntity<Map<String, Object>> costruisciRispostaEsecuzione(JobExecution execution, Map<String, Object> response) {
        if (execution.getStatus() == BatchStatus.COMPLETED) {
            response.put(RESPONSE_MESSAGE, "Job completato con successo");
            return ResponseEntity.ok(response);
        }

        if (execution.getStatus() == BatchStatus.FAILED) {
            response.put(RESPONSE_MESSAGE, "Job terminato con errori");
            return ResponseEntity.status(500).body(response);
        }

        response.put(RESPONSE_MESSAGE, "Job avviato");
        return ResponseEntity.ok(response);
    }
}
