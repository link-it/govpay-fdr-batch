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
     * Esegue il job FDR Acquisition manualmente.
     *
     * @return ResponseEntity con lo stato dell'esecuzione
     */
    @GetMapping("/eseguiJob")
    public ResponseEntity<Map<String, Object>> eseguiJob() {
        log.info("Richiesta esecuzione manuale del job {}", Costanti.FDR_ACQUISITION_JOB_NAME);

        Map<String, Object> response = new HashMap<>();
        response.put("jobName", Costanti.FDR_ACQUISITION_JOB_NAME);
        response.put("timestamp", OffsetDateTime.now().toString());

        try {
            // Verifica se il job è già in esecuzione
            JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                    .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

            if (currentRunningJobExecution != null) {
                // Verifica se il job è stale
                if (this.preventConcurrentJobLauncher.isJobExecutionStale(currentRunningJobExecution)) {
                    log.warn("JobExecution {} rilevata come STALE. Procedo con abbandono e riavvio.",
                            currentRunningJobExecution.getId());

                    if (this.preventConcurrentJobLauncher.abandonStaleJobExecution(currentRunningJobExecution)) {
                        log.info("Job stale abbandonato con successo. Avvio nuova esecuzione.");
                        // Procedi con l'esecuzione
                    } else {
                        response.put("status", "ERROR");
                        response.put("message", "Impossibile abbandonare il job stale");
                        response.put("staleJobId", currentRunningJobExecution.getId());
                        return ResponseEntity.status(503).body(response);
                    }
                } else {
                    String runningClusterId = this.preventConcurrentJobLauncher
                            .getClusterIdFromExecution(currentRunningJobExecution);
                    response.put("status", "ALREADY_RUNNING");
                    response.put("message", "Il job è già in esecuzione");
                    response.put("runningJobId", currentRunningJobExecution.getId());
                    response.put("runningOnCluster", runningClusterId);
                    return ResponseEntity.status(409).body(response);
                }
            }

            // Esegui il job
            JobParameters params = new JobParametersBuilder()
                    .addString(Costanti.GOVPAY_BATCH_JOB_ID, Costanti.FDR_ACQUISITION_JOB_NAME)
                    .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN, OffsetDateTime.now().toString())
                    .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, this.clusterId)
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(fdrAcquisitionJob, params);

            response.put("status", execution.getStatus().toString());
            response.put("jobExecutionId", execution.getId());
            response.put("exitCode", execution.getExitStatus().getExitCode());

            if (execution.getStatus() == BatchStatus.COMPLETED) {
                response.put("message", "Job completato con successo");
                return ResponseEntity.ok(response);
            } else if (execution.getStatus() == BatchStatus.FAILED) {
                response.put("message", "Job terminato con errori");
                return ResponseEntity.status(500).body(response);
            } else {
                response.put("message", "Job avviato");
                return ResponseEntity.ok(response);
            }

        } catch (Exception e) {
            log.error("Errore durante l'esecuzione del job: {}", e.getMessage(), e);
            response.put("status", "ERROR");
            response.put("message", "Errore durante l'esecuzione: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
