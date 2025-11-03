package it.govpay.fdr.batch.config;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.step2.FdrHeadersProcessor;
import it.govpay.fdr.batch.step2.FdrHeadersReader;
import it.govpay.fdr.batch.step2.FdrHeadersWriter;
import it.govpay.fdr.batch.step3.FdrPaymentsProcessor;
import it.govpay.fdr.batch.step3.FdrPaymentsReader;
import it.govpay.fdr.batch.step3.FdrPaymentsWriter;
import it.govpay.fdr.batch.tasklet.CleanupFrTempTasklet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClientException;

/**
 * Configuration for FDR Acquisition Batch Job
 */
@Configuration
@Slf4j
public class BatchJobConfiguration {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final BatchProperties batchProperties;
    private final PagoPAProperties pagoPAProperties;

    public BatchJobConfiguration(
        JobRepository jobRepository,
        PlatformTransactionManager transactionManager,
        BatchProperties batchProperties,
        PagoPAProperties pagoPAProperties
    ) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.batchProperties = batchProperties;
        this.pagoPAProperties = pagoPAProperties;
    }

    /**
     * Main FDR Acquisition Job with 3 steps
     */
    @Bean
    public Job fdrAcquisitionJob(
        Step cleanupStep,
        Step fdrHeadersAcquisitionStep,
        Step fdrPaymentsAcquisitionStep
    ) {
        return new JobBuilder("fdrAcquisitionJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(cleanupStep)
            .next(fdrHeadersAcquisitionStep)
            .next(fdrPaymentsAcquisitionStep)
            .build();
    }

    /**
     * Step 1: Cleanup FR_TEMP table
     */
    @Bean
    public Step cleanupStep(CleanupFrTempTasklet cleanupFrTempTasklet) {
        return new StepBuilder("cleanupStep", jobRepository)
            .tasklet(cleanupFrTempTasklet, transactionManager)
            .build();
    }

    /**
     * Step 2: Acquire FDR headers (multi-threaded)
     */
    @Bean
    public Step fdrHeadersAcquisitionStep(
        FdrHeadersReader fdrHeadersReader,
        FdrHeadersProcessor fdrHeadersProcessor,
        FdrHeadersWriter fdrHeadersWriter
    ) {
        return new StepBuilder("fdrHeadersAcquisitionStep", jobRepository)
            .<DominioProcessingContext, FdrHeadersBatch>chunk(1, transactionManager)
            .reader(fdrHeadersReader)
            .processor(fdrHeadersProcessor)
            .writer(fdrHeadersWriter)
            .faultTolerant()
            .retry(RestClientException.class)
            .retryLimit(pagoPAProperties.getMaxRetries())
            .skip(RestClientException.class)
            .skipLimit(batchProperties.getSkipLimit())
            .taskExecutor(taskExecutor())
            .throttleLimit(batchProperties.getThreadPoolSize())
            .build();
    }

    /**
     * Step 3: Acquire FDR payment details
     */
    @Bean
    public Step fdrPaymentsAcquisitionStep(
        FdrPaymentsReader fdrPaymentsReader,
        FdrPaymentsProcessor fdrPaymentsProcessor,
        FdrPaymentsWriter fdrPaymentsWriter
    ) {
        return new StepBuilder("fdrPaymentsAcquisitionStep", jobRepository)
            .<FrTemp, FdrPaymentsProcessor.FdrCompleteData>chunk(batchProperties.getChunkSize(), transactionManager)
            .reader(fdrPaymentsReader)
            .processor(fdrPaymentsProcessor)
            .writer(fdrPaymentsWriter)
            .faultTolerant()
            .retry(RestClientException.class)
            .retryLimit(pagoPAProperties.getMaxRetries())
            .skip(RestClientException.class)
            .skipLimit(batchProperties.getSkipLimit())
            .build();
    }

    /**
     * Task executor for parallel processing in Step 2
     */
    @Bean
    public SimpleAsyncTaskExecutor taskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("fdr-batch-");
        executor.setConcurrencyLimit(batchProperties.getThreadPoolSize());
        return executor;
    }

    /**
     * Retry template for API calls
     */
    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Retry policy
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(pagoPAProperties.getMaxRetries());
        retryTemplate.setRetryPolicy(retryPolicy);

        // Backoff policy
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(2000L); // 2 seconds between retries
        retryTemplate.setBackOffPolicy(backOffPolicy);

        return retryTemplate;
    }
}
