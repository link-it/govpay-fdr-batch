package it.govpay.fdr.batch.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PreventConcurrentJobLauncher;
import lombok.extern.slf4j.Slf4j;

/**
 * Controller REST per l'esecuzione manuale e il monitoraggio dei job batch.
 */
@Slf4j
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    private final JobLauncher jobLauncher;
    private final JobExplorer jobExplorer;
    private final PreventConcurrentJobLauncher preventConcurrentJobLauncher;
    private final Job fdrAcquisitionJob;
    private final Environment environment;

    @Value("${govpay.batch.cluster-id:GovPay-FDR-Batch}")
    private String clusterId;

    @Value("${scheduler.fdrAcquisitionJob.fixedDelayString:7200000}")
    private long schedulerIntervalMillis;

    public BatchController(
            JobLauncher jobLauncher,
            JobExplorer jobExplorer,
            PreventConcurrentJobLauncher preventConcurrentJobLauncher,
            @Qualifier("fdrAcquisitionJob") Job fdrAcquisitionJob,
            Environment environment) {
        this.jobLauncher = jobLauncher;
        this.jobExplorer = jobExplorer;
        this.preventConcurrentJobLauncher = preventConcurrentJobLauncher;
        this.fdrAcquisitionJob = fdrAcquisitionJob;
        this.environment = environment;
    }

    /**
     * Esegue il job FDR Acquisition manualmente in modo asincrono.
     * <p>
     * Il servizio avvia il job e restituisce immediatamente la risposta senza attendere
     * la terminazione del batch. Lo stato del job può essere verificato tramite le
     * tabelle Spring Batch o i log.
     *
     * @param force Se true, termina forzatamente l'eventuale esecuzione corrente e avvia una nuova esecuzione
     * @return ResponseEntity con lo stato dell'avvio o Problem in caso di errore
     */
    @GetMapping("/eseguiJob")
    public ResponseEntity<Object> eseguiJob(
            @RequestParam(name = "force", required = false, defaultValue = "false") boolean force) {
        log.info("Richiesta esecuzione manuale del job {} (force={})", Costanti.FDR_ACQUISITION_JOB_NAME, force);

        try {
            ResponseEntity<Object> runningJobResponse = gestisciJobInEsecuzione(force);
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
    private ResponseEntity<Object> gestisciJobInEsecuzione(boolean force) {
        JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

        if (currentRunningJobExecution == null) {
            return null;
        }

        if (force) {
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
        log.warn("Parametro force=true: terminazione forzata di JobExecution {}",
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
                "Il job %s è già in esecuzione (JobExecution ID: %d, Cluster: %s). Usa il parametro force=true per terminarlo forzatamente.",
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

    // ============ ENDPOINT DI MONITORAGGIO ============

    /**
     * Verifica se il batch è attualmente in esecuzione e da quanto tempo.
     *
     * @return BatchStatusInfo con le informazioni sullo stato corrente
     */
    @GetMapping("/status")
    public ResponseEntity<BatchStatusInfo> getStatus() {
        log.debug("Richiesta stato del batch {}", Costanti.FDR_ACQUISITION_JOB_NAME);

        JobExecution currentExecution = this.preventConcurrentJobLauncher
                .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

        if (currentExecution == null) {
            return ResponseEntity.ok(BatchStatusInfo.builder()
                    .running(false)
                    .build());
        }

        // Calcola la durata dell'esecuzione
        Long runningSeconds = null;
        if (currentExecution.getStartTime() != null) {
            Duration duration = Duration.between(currentExecution.getStartTime(), LocalDateTime.now());
            runningSeconds = duration.getSeconds();
        }

        // Trova lo step corrente in esecuzione
        String currentStep = currentExecution.getStepExecutions().stream()
                .filter(se -> se.getStatus() == BatchStatus.STARTED)
                .map(StepExecution::getStepName)
                .findFirst()
                .orElse(null);

        String runningClusterId = this.preventConcurrentJobLauncher.getClusterIdFromExecution(currentExecution);

        return ResponseEntity.ok(BatchStatusInfo.builder()
                .running(true)
                .executionId(currentExecution.getId())
                .clusterId(runningClusterId)
                .startTime(currentExecution.getStartTime())
                .runningSeconds(runningSeconds)
                .status(currentExecution.getStatus().name())
                .currentStep(currentStep)
                .build());
    }

    /**
     * Restituisce le informazioni sull'ultima esecuzione completata del batch.
     *
     * @return LastExecutionInfo con le informazioni sull'ultima esecuzione
     */
    @GetMapping("/lastExecution")
    public ResponseEntity<LastExecutionInfo> getLastExecution() {
        log.debug("Richiesta ultima esecuzione del batch {}", Costanti.FDR_ACQUISITION_JOB_NAME);

        // Trova l'ultima istanza del job
        List<JobInstance> jobInstances = jobExplorer.getJobInstances(Costanti.FDR_ACQUISITION_JOB_NAME, 0, 10);

        if (jobInstances.isEmpty()) {
            return ResponseEntity.ok(LastExecutionInfo.builder().build());
        }

        // Cerca l'ultima esecuzione completata (non STARTED)
        for (JobInstance jobInstance : jobInstances) {
            List<JobExecution> executions = jobExplorer.getJobExecutions(jobInstance);
            for (JobExecution execution : executions) {
                if (execution.getStatus() != BatchStatus.STARTED
                        && execution.getStatus() != BatchStatus.STARTING
                        && execution.getStatus() != BatchStatus.STOPPING) {

                    Long durationSeconds = null;
                    if (execution.getStartTime() != null && execution.getEndTime() != null) {
                        Duration duration = Duration.between(execution.getStartTime(), execution.getEndTime());
                        durationSeconds = duration.getSeconds();
                    }

                    String exitDescription = execution.getExitStatus() != null
                            ? execution.getExitStatus().getExitDescription()
                            : null;
                    // Truncate exit description if too long
                    if (exitDescription != null && exitDescription.length() > 500) {
                        exitDescription = exitDescription.substring(0, 500) + "...";
                    }

                    String runningClusterId = this.preventConcurrentJobLauncher.getClusterIdFromExecution(execution);

                    return ResponseEntity.ok(LastExecutionInfo.builder()
                            .executionId(execution.getId())
                            .clusterId(runningClusterId)
                            .startTime(execution.getStartTime())
                            .endTime(execution.getEndTime())
                            .durationSeconds(durationSeconds)
                            .status(execution.getStatus().name())
                            .exitCode(execution.getExitStatus() != null ? execution.getExitStatus().getExitCode() : null)
                            .exitDescription(exitDescription)
                            .build());
                }
            }
        }

        return ResponseEntity.ok(LastExecutionInfo.builder().build());
    }

    /**
     * Restituisce le informazioni sulla prossima esecuzione schedulata.
     *
     * @return NextExecutionInfo con le informazioni sulla prossima esecuzione
     */
    @GetMapping("/nextExecution")
    public ResponseEntity<NextExecutionInfo> getNextExecution() {
        log.debug("Richiesta prossima esecuzione del batch {}", Costanti.FDR_ACQUISITION_JOB_NAME);

        // Verifica se siamo in modalità cron (esterna)
        boolean isCronMode = environment.matchesProfiles("cron");

        if (isCronMode) {
            return ResponseEntity.ok(NextExecutionInfo.builder()
                    .schedulingMode("cron")
                    .message("Scheduling gestito da cron esterno (OS/container)")
                    .build());
        }

        // Modalità scheduler interno
        String intervalFormatted = formatInterval(schedulerIntervalMillis);

        // Trova l'ultima esecuzione completata per calcolare la prossima
        LocalDateTime lastCompletedTime = null;
        LocalDateTime nextExecutionTime = null;

        List<JobInstance> jobInstances = jobExplorer.getJobInstances(Costanti.FDR_ACQUISITION_JOB_NAME, 0, 5);
        for (JobInstance jobInstance : jobInstances) {
            List<JobExecution> executions = jobExplorer.getJobExecutions(jobInstance);
            for (JobExecution execution : executions) {
                if (execution.getEndTime() != null) {
                    lastCompletedTime = execution.getEndTime();
                    // La prossima esecuzione è dopo l'intervallo configurato
                    nextExecutionTime = lastCompletedTime.plusNanos(schedulerIntervalMillis * 1_000_000);
                    break;
                }
            }
            if (lastCompletedTime != null) break;
        }

        // Se non c'è mai stata un'esecuzione, la prossima sarà immediata (o quasi)
        if (nextExecutionTime == null) {
            nextExecutionTime = LocalDateTime.now();
        }

        // Se la prossima esecuzione è nel passato, significa che il batch è in attesa
        if (nextExecutionTime.isBefore(LocalDateTime.now())) {
            // Verifica se c'è un job in esecuzione
            JobExecution currentExecution = this.preventConcurrentJobLauncher
                    .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);
            if (currentExecution != null) {
                nextExecutionTime = null; // In esecuzione, non c'è prossima schedulata
            } else {
                nextExecutionTime = LocalDateTime.now(); // Dovrebbe partire a breve
            }
        }

        return ResponseEntity.ok(NextExecutionInfo.builder()
                .schedulingMode("scheduler")
                .nextExecutionTime(nextExecutionTime)
                .intervalMillis(schedulerIntervalMillis)
                .intervalFormatted(intervalFormatted)
                .lastCompletedTime(lastCompletedTime)
                .build());
    }

    /**
     * Formatta un intervallo in millisecondi in formato human-readable.
     */
    private String formatInterval(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            long remainingMinutes = minutes % 60;
            if (remainingMinutes > 0) {
                return String.format("%d ore %d minuti", hours, remainingMinutes);
            }
            return String.format("%d ore", hours);
        } else if (minutes > 0) {
            return String.format("%d minuti", minutes);
        } else {
            return String.format("%d secondi", seconds);
        }
    }
}
