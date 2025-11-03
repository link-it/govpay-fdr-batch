package it.govpay.fdr.batch.scheduler;

import it.govpay.fdr.batch.config.BatchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for FDR Acquisition Batch Job
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "govpay.batch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FdrBatchScheduler {

    private final JobLauncher jobLauncher;
    private final Job fdrAcquisitionJob;
    private final BatchProperties batchProperties;

    public FdrBatchScheduler(
        JobLauncher jobLauncher,
        Job fdrAcquisitionJob,
        BatchProperties batchProperties
    ) {
        this.jobLauncher = jobLauncher;
        this.fdrAcquisitionJob = fdrAcquisitionJob;
        this.batchProperties = batchProperties;
    }

    /**
     * Scheduled execution of FDR Acquisition Job
     */
    @Scheduled(cron = "${govpay.batch.cron}")
    public void runFdrAcquisitionJob() {
        log.info("Starting scheduled FDR Acquisition Job");

        try {
            JobParameters jobParameters = new JobParametersBuilder()
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

            jobLauncher.run(fdrAcquisitionJob, jobParameters);

            log.info("FDR Acquisition Job completed successfully");

        } catch (Exception e) {
            log.error("Error executing FDR Acquisition Job: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual trigger for testing
     */
    public void triggerManually() {
        log.info("Manually triggering FDR Acquisition Job");
        runFdrAcquisitionJob();
    }
}
