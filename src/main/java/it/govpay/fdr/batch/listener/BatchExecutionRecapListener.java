package it.govpay.fdr.batch.listener;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Listener che stampa un riepilogo dettagliato dell'esecuzione del batch per ogni dominio.
 */
@Component
@Slf4j
public class BatchExecutionRecapListener implements JobExecutionListener {

    private static final String METADATA = "METADATA";
	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("=".repeat(80));
        log.info("INIZIO BATCH ACQUISIZIONE FDR");
        log.info("Job ID: {}", jobExecution.getJobId());
        log.info("Avvio: {}", LocalDateTime.now().format(TIME_FORMATTER));
        log.info("=".repeat(80));
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        log.info("=".repeat(80));
        log.info("RIEPILOGO ESECUZIONE BATCH");
        log.info("=".repeat(80));

        // Statistiche generali
        Duration duration = Duration.between(
            jobExecution.getStartTime(),
            jobExecution.getEndTime()
        );

        log.info("Status finale: {}", jobExecution.getStatus());
        log.info("Durata totale: {} secondi", duration.getSeconds());
        log.info("");

        // Statistiche per step
        printStepStatistics(jobExecution);

        log.info("=".repeat(80));
    }

    private void printStepStatistics(JobExecution jobExecution) {
        Collection<StepExecution> stepExecutions = jobExecution.getStepExecutions();

        // Step 1: Cleanup
        stepExecutions.stream()
            .filter(se -> se.getStepName().equals("cleanupStep"))
            .findFirst()
            .ifPresent(this::printCleanupStats);

        // Step 2: Headers Acquisition
        stepExecutions.stream()
            .filter(se -> se.getStepName().equals("fdrHeadersAcquisitionStep"))
            .findFirst()
            .ifPresent(this::printHeadersStats);

        // Step 3: Metadata Acquisition (partitioned)
        stepExecutions.stream()
            .filter(se -> se.getStepName().equals("fdrMetadataAcquisitionStep"))
            .findFirst()
            .ifPresent(se -> printPartitionedStepStats(se, METADATA));

        // Step 4: Payments Acquisition (partitioned)
        stepExecutions.stream()
            .filter(se -> se.getStepName().equals("fdrPaymentsAcquisitionStep"))
            .findFirst()
            .ifPresent(se -> printPartitionedStepStats(se, "PAGAMENTI"));
    }

    private void printCleanupStats(StepExecution stepExecution) {
        log.info("--- STEP 1: CLEANUP FR_TEMP ---");
        log.info("Status: {}", stepExecution.getStatus());
        long durationMs = Duration.between(stepExecution.getStartTime(), stepExecution.getEndTime()).toMillis();
        log.info("Durata: {} ms", durationMs);
        log.info("");
    }

    private void printHeadersStats(StepExecution stepExecution) {
        log.info("--- STEP 2: ACQUISIZIONE HEADERS ---");
        log.info("Status: {}", stepExecution.getStatus());
        log.info("Domini processati: {}", stepExecution.getReadCount());

        // Leggi statistiche dal contesto dello step (impostate da FdrHeadersWriter)
        int savedCount = stepExecution.getExecutionContext().getInt("headersSavedCount", 0);
        int skippedFrCount = stepExecution.getExecutionContext().getInt("headersSkippedFrCount", 0);
        int skippedFrTempCount = stepExecution.getExecutionContext().getInt("headersSkippedFrTempCount", 0);
        int totalSkipped = skippedFrCount + skippedFrTempCount;

        log.info("Flussi salvati in FR_TEMP: {}", savedCount);
        log.info("Flussi skippati (già in FR): {}", skippedFrCount);
        log.info("Flussi skippati (già in FR_TEMP): {}", skippedFrTempCount);
        log.info("Totale flussi skippati: {}", totalSkipped);
        log.info("Errori: {}", stepExecution.getReadSkipCount() + stepExecution.getProcessSkipCount());
        long durationMs = Duration.between(stepExecution.getStartTime(), stepExecution.getEndTime()).toMillis();
        log.info("Durata: {} ms", durationMs);
        log.info("");
    }

    private void printPartitionedStepStats(StepExecution masterStepExecution, String stepType) {
        log.info("--- STEP {}: ACQUISIZIONE {} (PARTIZIONATO) ---",
            stepType.equals(METADATA) ? "3" : "4", stepType);
        log.info("Status master step: {}", masterStepExecution.getStatus());

        // Statistiche aggregate dalle partizioni
        Collection<StepExecution> partitionSteps = masterStepExecution.getJobExecution().getStepExecutions().stream()
            .filter(se -> se.getStepName().startsWith(stepType.equals(METADATA)
                ? "fdrMetadataWorkerStep"
                : "fdrPaymentsWorkerStep"))
            .toList();

        if (partitionSteps.isEmpty()) {
            log.info("Nessuna partizione eseguita (nessun dominio da processare)");
            log.info("");
            return;
        }

        int totalRead = 0;
        int totalWritten = 0;
        int totalSkipped = 0;
        int totalErrors = 0;
        long totalDuration = 0;
        Map<String, PartitionStats> domainStats = new LinkedHashMap<>();

        for (StepExecution partitionExec : partitionSteps) {
            totalRead += partitionExec.getReadCount();
            totalWritten += partitionExec.getWriteCount();
            totalSkipped += (int) partitionExec.getWriteSkipCount();
            totalErrors += (int) (partitionExec.getReadSkipCount() + partitionExec.getProcessSkipCount());

            long duration = Duration.between(partitionExec.getStartTime(), partitionExec.getEndTime()).toMillis();
            totalDuration += duration;

            // Estrai codDominio dal nome della partizione o dal context
            String codDominio = extractCodDominio(partitionExec);
            if (codDominio != null) {
                PartitionStats stats = new PartitionStats();
                stats.codDominio = codDominio;
                stats.readCount = (int) partitionExec.getReadCount();
                stats.writeCount = (int) partitionExec.getWriteCount();
                stats.skipCount = (int) partitionExec.getWriteSkipCount();
                stats.errorCount = (int) (partitionExec.getReadSkipCount() + partitionExec.getProcessSkipCount());
                stats.status = partitionExec.getStatus().toString();
                stats.durationMs = duration;
                domainStats.put(codDominio, stats);
            }
        }

        log.info("Partizioni totali: {}", partitionSteps.size());
        log.info("Flussi letti: {}", totalRead);
        log.info("Flussi processati: {}", totalWritten);
        log.info("Flussi skippati: {}", totalSkipped);
        log.info("Errori: {}", totalErrors);
        log.info("Durata totale: {} secondi", totalDuration / 1000);
        log.info("");

        // Dettaglio per dominio
        if (!domainStats.isEmpty()) {
            log.info("Dettaglio per dominio:");
            log.info("-".repeat(80));
            log.info(String.format("%-20s %-10s %-10s %-10s %-10s %-15s %-10s",
                "DOMINIO", "LETTI", "PROCESSATI", "SKIPPATI", "ERRORI", "STATUS", "DURATA(s)"));
            log.info("-".repeat(80));

            domainStats.values().forEach(stats -> 
                log.info(String.format("%-20s %-10d %-10d %-10d %-10d %-15s %-10.1f",
                    stats.codDominio,
                    stats.readCount,
                    stats.writeCount,
                    stats.skipCount,
                    stats.errorCount,
                    stats.status,
                    stats.durationMs / 1000.0
                ))
            );
            log.info("-".repeat(80));
        }
        log.info("");
    }

    private String extractCodDominio(StepExecution stepExecution) {
        // Prova a estrarre codDominio dall'execution context
        if (stepExecution.getExecutionContext().containsKey("codDominio")) {
            return stepExecution.getExecutionContext().getString("codDominio");
        }

        // Altrimenti dal nome dello step (formato: stepName:partition-CODDOMINIO)
        String stepName = stepExecution.getStepName();
        if (stepName.contains(":partition-")) {
            return stepName.substring(stepName.indexOf(":partition-") + 11);
        }

        return "unknown";
    }

    private static class PartitionStats {
        String codDominio;
        int readCount;
        int writeCount;
        int skipCount;
        int errorCount;
        String status;
        long durationMs;
    }
}
