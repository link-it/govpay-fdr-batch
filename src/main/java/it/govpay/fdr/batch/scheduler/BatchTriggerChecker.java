package it.govpay.fdr.batch.scheduler;

import java.time.OffsetDateTime;
import java.util.Date;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.config.PreventConcurrentJobLauncher;
import it.govpay.fdr.batch.entity.Batch;
import it.govpay.fdr.batch.repository.BatchRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Componente che verifica periodicamente la tabella BATCH per rilevare
 * richieste di attivazione manuale da GovPay.
 * <p>
 * Quando rileva una richiesta (campo aggiornamento modificato), lancia
 * immediatamente il job batch.
 * <p>
 * Questo componente è attivo solo quando {@code govpay.batch.trigger.enabled=true}.
 * <p>
 * Prima di avviare il job, verifica che non sia già in esecuzione su questo nodo
 * o su altri nodi del cluster.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "govpay.batch.trigger", name = "enabled", havingValue = "true", matchIfMissing = false)
@Profile("default")
public class BatchTriggerChecker {

    private static final String BATCH_CODE = "batch_fdr";

    private final BatchRepository batchRepository;
    private final JobLauncher jobLauncher;
    private final PreventConcurrentJobLauncher preventConcurrentJobLauncher;
    @Qualifier("fdrAcquisitionJob")
    private final Job fdrAcquisitionJob;

    @Value("${govpay.batch.cluster-id:govpay-fdr-batch}")
    private String clusterId;

    /** Timestamp dell'ultima esecuzione verificata */
    private Date ultimoCheck = new Date();

    /** Flag per evitare esecuzioni multiple contemporanee */
    private volatile boolean inEsecuzione = false;

    public BatchTriggerChecker(
            BatchRepository batchRepository,
            JobLauncher jobLauncher,
            PreventConcurrentJobLauncher preventConcurrentJobLauncher,
            @Qualifier("fdrAcquisitionJob") Job fdrAcquisitionJob) {
        this.batchRepository = batchRepository;
        this.jobLauncher = jobLauncher;
        this.preventConcurrentJobLauncher = preventConcurrentJobLauncher;
        this.fdrAcquisitionJob = fdrAcquisitionJob;
        log.info("BatchTriggerChecker inizializzato per batch [{}]", BATCH_CODE);
    }

    /**
     * Verifica periodicamente se c'è una richiesta di attivazione manuale.
     * L'intervallo è configurabile tramite la property {@code govpay.batch.trigger.interval}.
     */
    @Scheduled(fixedDelayString = "${govpay.batch.trigger.interval:10000}",
               initialDelayString = "${govpay.batch.trigger.initial-delay:10000}")
    public void checkAttivazioneManualeEEsegui() {

        // Evita esecuzioni sovrapposte
        if (inEsecuzione) {
            log.trace("Check già in esecuzione, skip");
            return;
        }

        try {
            log.trace("Check attivazione manuale batch [{}]...", BATCH_CODE);

            if (this.isAttivazioneManualeRichiesta()) {
                log.info("*** Rilevata richiesta di attivazione manuale per batch [{}] ***", BATCH_CODE);
                eseguiBatchManuale();
            }

        } catch (Exception e) {
            log.error("Errore durante il check di attivazione manuale: {}", e.getMessage(), e);
        }
    }

    /**
     * Verifica se c'è una richiesta di esecuzione pendente.
     */
    @Transactional(readOnly = true)
    protected boolean isAttivazioneManualeRichiesta() {
        try {
            Batch batch = batchRepository.findByCodBatch(BATCH_CODE).orElse(null);

            if (batch == null) {
                log.trace("Record batch [{}] non trovato", BATCH_CODE);
                return false;
            }

            Date aggiornamento = batch.getAggiornamento();

            if (aggiornamento != null && ultimoCheck.getTime() < aggiornamento.getTime()) {
                log.info("Attivazione manuale richiesta: timestamp DB: {}, ultimo check: {}, nodo: {}",
                        aggiornamento, ultimoCheck, batch.getNodo());
                return true;
            }

            return false;

        } catch (Exception e) {
            log.warn("Errore durante la lettura della tabella batch: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Esegue il batch in modalità manuale.
     */
    protected void eseguiBatchManuale() {
        inEsecuzione = true;

        try {
            // Verifica che il job non sia già in esecuzione
            JobExecution currentRunningJobExecution = this.preventConcurrentJobLauncher
                    .getCurrentRunningJobExecution(Costanti.FDR_ACQUISITION_JOB_NAME);

            if (currentRunningJobExecution != null) {
                String runningClusterId = this.preventConcurrentJobLauncher.getClusterIdFromExecution(currentRunningJobExecution);
                log.warn("Il job {} è già in esecuzione sul nodo {}. Attivazione manuale ignorata.",
                        Costanti.FDR_ACQUISITION_JOB_NAME, runningClusterId);
                // Aggiorna comunque ultimoCheck per evitare tentativi ripetuti
                ultimoCheck = new Date();
                return;
            }

            log.info("=== AVVIO BATCH FDR (ATTIVAZIONE MANUALE) ===");

            // Prepara i parametri del job
            JobParameters params = new JobParametersBuilder()
                    .addString(Costanti.GOVPAY_BATCH_JOB_ID, Costanti.FDR_ACQUISITION_JOB_NAME)
                    .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_WHEN, OffsetDateTime.now().toString())
                    .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_CLUSTER_ID, this.clusterId)
                    .addString(Costanti.GOVPAY_BATCH_JOB_PARAMETER_TIPO_ATTIVAZIONE, Costanti.TIPO_ATTIVAZIONE_MANUALE)
                    .toJobParameters();

            // Lancia il job
            JobExecution execution = jobLauncher.run(fdrAcquisitionJob, params);

            log.info("Batch FDR completato - Stato: {}, Exit Code: {}",
                    execution.getStatus(), execution.getExitStatus().getExitCode());

            // Registra l'esecuzione nel database
            registraEsecuzione();

            // Aggiorna il timestamp locale
            ultimoCheck = new Date();

            log.info("=== FINE BATCH FDR (ATTIVAZIONE MANUALE) ===");

        } catch (Exception e) {
            log.error("Errore durante l'esecuzione del batch manuale: {}", e.getMessage(), e);

            // In caso di errore, aggiorna comunque ultimoCheck per evitare tentativi ripetuti
            ultimoCheck = new Date();

        } finally {
            inEsecuzione = false;
        }
    }

    /**
     * Registra nel database che il batch è stato eseguito.
     */
    @Transactional
    protected void registraEsecuzione() {
        try {
            Date now = new Date();

            Batch batch = batchRepository.findByCodBatch(BATCH_CODE)
                    .orElseGet(() -> {
                        Batch newBatch = new Batch();
                        newBatch.setCodBatch(BATCH_CODE);
                        return newBatch;
                    });

            // Aggiorna il timestamp di inizio esecuzione e il nodo
            batch.setInizio(now);
            batch.setNodo(clusterId);
            batchRepository.save(batch);

            log.debug("Registrata esecuzione batch [{}] con timestamp: {}", BATCH_CODE, now);

        } catch (Exception e) {
            log.error("Errore durante la registrazione dell'esecuzione: {}", e.getMessage(), e);
        }
    }
}
