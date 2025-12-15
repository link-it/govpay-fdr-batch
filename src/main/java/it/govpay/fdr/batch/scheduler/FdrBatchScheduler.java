package it.govpay.fdr.batch.scheduler;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Scheduler for FDR Acquisition Batch Job
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "govpay.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FdrBatchScheduler {

    private final JobLauncher jobLauncher;
    private final Job fdrAcquisitionJob;

    public FdrBatchScheduler(
        JobLauncher jobLauncher,
        Job fdrAcquisitionJob
    ) {
        this.jobLauncher = jobLauncher;
        this.fdrAcquisitionJob = fdrAcquisitionJob;
    }

    /**
     * Scheduled execution of FDR Acquisition Job
     */
    @Scheduled(cron = "${govpay.batch.cron}")
    public JobExecution runFdrAcquisitionJob() {
        log.info("Starting scheduled FDR Acquisition Job");

        JobExecution res = null;
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

            res = jobLauncher.run(fdrAcquisitionJob, jobParameters);

            log.info("FDR Acquisition Job completed successfully");

        } catch (Exception e) {
            log.error("Errore nell'esecuzione del Job di Acquisizione FDR: {}", e.getMessage(), e);
        }
        return res;
    }

    /**
     * Manual trigger for testing
     */
    public void triggerManually() {
        log.info("Manually triggering FDR Acquisition Job");
        runFdrAcquisitionJob();
    }
}
