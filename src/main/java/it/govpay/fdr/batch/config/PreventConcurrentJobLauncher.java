package it.govpay.fdr.batch.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service per prevenire l'esecuzione concorrente di job Spring Batch in ambiente multi-nodo.
 * <p>
 * Verifica se un job è già in esecuzione utilizzando il JobExplorer di Spring Batch.
 * Questo consente il coordinamento tra nodi diversi che condividono lo stesso database
 * per i metadati di Spring Batch.
 * <p>
 * Funzionalità avanzate:
 * - Rilevamento job "stale" (bloccati da troppo tempo)
 * - Rilevamento job in stati anomali
 * - Terminazione forzata e recupero automatico
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreventConcurrentJobLauncher {

    private final JobExplorer jobExplorer;
    private final JobRepository jobRepository;

    @Value("${govpay.batch.max-execution-hours:2}")
    private long maxExecutionHours;

    /**
     * Controlla e restituisce l'esecuzione corrente del job, se esiste.
     * <p>
     * Utilizza il JobExplorer per interrogare le tabelle BATCH_* nel database
     * e verificare se esistono esecuzioni in corso per il job specificato.
     *
     * @param jobName Nome del job da verificare
     * @return l'esecuzione corrente del job oppure null se non ce ne sono
     */
    public JobExecution getCurrentRunningJobExecution(String jobName) {
        Set<JobExecution> runningJobs = jobExplorer.findRunningJobExecutions(jobName);

        if (!runningJobs.isEmpty()) {
            // Restituisce la prima esecuzione in corso
            List<JobExecution> list = runningJobs.stream().toList();

            log.info("Trovati {} job '{}' in esecuzione:", list.size(), jobName);
            for (JobExecution je : list) {
                log.info("  - JobExecution ID: {}, JobInstance: {}, Status: {}, Start: {}",
                    je.getId(), je.getJobInstance().getJobName(), je.getStatus(), je.getStartTime());
            }

            return list.get(0);
        }

        return null;
    }

    /**
     * Verifica se un'esecuzione è "stale" (bloccata o in stato anomalo).
     * <p>
     * Un'esecuzione è considerata stale se:
     * - È in esecuzione da più tempo del timeout configurato (maxExecutionHours)
     * - È in uno stato anomalo (UNKNOWN, ABANDONED)
     * - È marcata come STARTED ma senza progressi recenti
     *
     * @param jobExecution L'esecuzione da verificare
     * @return true se l'esecuzione è stale, false altrimenti
     */
    public boolean isJobExecutionStale(JobExecution jobExecution) {
        if (jobExecution == null) {
            return false;
        }

        BatchStatus status = jobExecution.getStatus();
        LocalDateTime startTime = jobExecution.getStartTime();

        // Verifica stati anomali
        if (status == BatchStatus.UNKNOWN || status == BatchStatus.ABANDONED) {
            log.warn("JobExecution {} è in stato anomalo: {}", jobExecution.getId(), status);
            return true;
        }

        // Verifica timeout
        if (startTime != null && status == BatchStatus.STARTED) {
            LocalDateTime now = LocalDateTime.now();
            Duration duration = Duration.between(startTime, now);
            long hoursRunning = duration.toHours();

            if (hoursRunning > maxExecutionHours) {
                log.warn("JobExecution {} è in esecuzione da {} ore (limite: {} ore). Considerata stale.",
                    jobExecution.getId(), hoursRunning, maxExecutionHours);
                return true;
            }

            log.debug("JobExecution {} è in esecuzione da {} ore (limite: {} ore)",
                jobExecution.getId(), hoursRunning, maxExecutionHours);
        }

        return false;
    }

    /**
     * Abbandona forzatamente un'esecuzione stale, permettendo il riavvio del job.
     * <p>
     * Aggiorna lo stato dell'esecuzione a FAILED nel database Spring Batch,
     * liberando il lock e permettendo a un nuovo nodo di avviare il job.
     * <p>
     * IMPORTANTE: Questa operazione non termina il processo Java che sta
     * eseguendo il job bloccato, ma solo aggiorna i metadati nel database.
     * Il processo bloccato potrebbe continuare a girare fino al suo timeout.
     *
     * @param jobExecution L'esecuzione da abbandonare
     * @return true se l'abbandono è riuscito, false altrimenti
     */
    public boolean abandonStaleJobExecution(JobExecution jobExecution) {
        if (jobExecution == null) {
            return false;
        }

        try {
            log.warn("Abbandono forzato JobExecution {} (Status: {}, Start: {})",
                jobExecution.getId(), jobExecution.getStatus(), jobExecution.getStartTime());

            // Aggiorna lo stato a FAILED e imposta end time
            jobExecution.setStatus(BatchStatus.FAILED);
            jobExecution.setEndTime(LocalDateTime.now());
            jobExecution.setExitStatus(org.springframework.batch.core.ExitStatus.FAILED
                .addExitDescription("Job abbandonato automaticamente: esecuzione stale rilevata dopo "
                    + maxExecutionHours + " ore o stato anomalo"));

            // Aggiorna anche tutti gli step in esecuzione
            jobExecution.getStepExecutions().forEach(stepExecution -> {
                if (stepExecution.getStatus() == BatchStatus.STARTED) {
                    stepExecution.setStatus(BatchStatus.FAILED);
                    stepExecution.setEndTime(LocalDateTime.now());
                    stepExecution.setExitStatus(org.springframework.batch.core.ExitStatus.FAILED
                        .addExitDescription("Step abbandonato: job stale"));
                    jobRepository.update(stepExecution);
                }
            });

            // Aggiorna il job execution nel repository
            jobRepository.update(jobExecution);

            log.info("JobExecution {} abbandonata con successo", jobExecution.getId());
            return true;

        } catch (Exception e) {
            log.error("Errore nell'abbandono di JobExecution {}: {}",
                jobExecution.getId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Estrae il cluster ID da un'esecuzione di job.
     * <p>
     * Il cluster ID identifica il nodo che ha avviato l'esecuzione.
     *
     * @param jobExecution L'esecuzione del job
     * @return il cluster ID oppure null se non presente o se jobExecution è null
     */
    public String getClusterIdFromExecution(JobExecution jobExecution) {
        if (jobExecution == null) {
            return null;
        }

        var params = jobExecution.getJobParameters().getParameters();
        if (params.containsKey(it.govpay.fdr.batch.Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID)) {
            return params.get(it.govpay.fdr.batch.Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID)
                .getValue().toString();
        }

        return null;
    }
}
