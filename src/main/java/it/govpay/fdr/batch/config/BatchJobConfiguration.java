package it.govpay.fdr.batch.config;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.step2.FdrHeadersProcessor;
import it.govpay.fdr.batch.step2.FdrHeadersReader;
import it.govpay.fdr.batch.step2.FdrHeadersWriter;
import it.govpay.fdr.batch.step3.FdrMetadataProcessor;
import it.govpay.fdr.batch.step3.FdrMetadataReader;
import it.govpay.fdr.batch.step3.FdrMetadataWriter;
import it.govpay.fdr.batch.step4.FdrPaymentsProcessor;
import it.govpay.fdr.batch.step4.FdrPaymentsReader;
import it.govpay.fdr.batch.step4.FdrPaymentsWriter;
import it.govpay.fdr.batch.tasklet.CleanupFrTempTasklet;
import lombok.extern.slf4j.Slf4j;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.skip.AlwaysSkipItemSkipPolicy;
import org.springframework.batch.core.step.skip.SkipPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
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

	private RetryPolicy retryPolicy() {
		Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(RestClientException.class, true);
        
        // Non ritentare su queste eccezioni
        retryableExceptions.put(IllegalArgumentException.class, false);
        retryableExceptions.put(NullPointerException.class, false);
        
        return new SimpleRetryPolicy(pagoPAProperties.getMaxRetries(), retryableExceptions);
	}

	private BackOffPolicy backOffPolicy() {
		ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(2000L); // 2 seconds between retries
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000L);
        return backOffPolicy;
	}

    /**
     * Main FDR Acquisition Job with 3 steps
     */
    @Bean
    public Job fdrAcquisitionJob(
        Step cleanupStep,
        Step fdrHeadersAcquisitionStep,
        Step fdrMetadataAcquisitionStep,
        Step fdrPaymentsAcquisitionStep
    ) {
        return new JobBuilder("fdrAcquisitionJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .start(cleanupStep)
            .next(fdrHeadersAcquisitionStep)
            .next(fdrMetadataAcquisitionStep)
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

    @Bean
    public RetryPolicy fdrHeadersRetryPolicy() {
    	return retryPolicy();
    }

    @Bean
    public BackOffPolicy fdrHeadersBackOffPolicy() {
        return backOffPolicy();
    }

    @Bean
    public RetryListener fdrHeadersRetryListener() {
    	return new RetryListener() {
        	@Override
        	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
        		log.info("Retry headers attempt started");
        		return true;
            }
            
            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info("Retry headers attempt closed");
            }
            
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info(MessageFormat.format("Retry headers attempt #{0} failed: {1}", context.getRetryCount(), throwable.getMessage()));
            }
        };
    }

    @Bean
    public SkipPolicy fdrHeadersSkipPolicy() {
    	return new AlwaysSkipItemSkipPolicy();
    }

    /**
     * Step 2: Acquire FDR headers (multi-threaded)
     */
    @Bean
    public Step fdrHeadersAcquisitionStep(
        FdrHeadersReader fdrHeadersReader,
        RetryPolicy fdrHeadersRetryPolicy,
        BackOffPolicy fdrHeadersBackOffPolicy,
        RetryListener fdrHeadersRetryListener,
        SkipPolicy fdrHeadersSkipPolicy,
        FdrHeadersProcessor fdrHeadersProcessor,
        FdrHeadersWriter fdrHeadersWriter
    ) {
        return new StepBuilder("fdrHeadersAcquisitionStep", jobRepository)
            .<DominioProcessingContext, FdrHeadersBatch>chunk(batchProperties.getHeadersChunkSize(), transactionManager)
            .reader(fdrHeadersReader)
            .processor(fdrHeadersProcessor)
            .writer(fdrHeadersWriter)
            .faultTolerant()
            .retryPolicy(fdrHeadersRetryPolicy)
            .backOffPolicy(fdrHeadersBackOffPolicy)
            .retry(RestClientException.class)
            .skipPolicy(fdrHeadersSkipPolicy)
            .listener(fdrHeadersRetryListener)
            .listener(fdrHeadersReader) // Register reader as step listener for queue reset
            .taskExecutor(taskExecutor())
            .build();
    }

    @Bean
    public RetryPolicy fdrMetadataRetryPolicy() {
    	return retryPolicy();
    }

    @Bean
    public BackOffPolicy fdrMetadataBackOffPolicy() {
        return backOffPolicy();
    }

    @Bean
    public RetryListener fdrMetadataRetryListener() {
    	return new RetryListener() {
        	@Override
        	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
        		log.info("Retry metadata attempt started");
        		return true;
            }
            
            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info("Retry metadata attempt closed");
            }
            
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info(MessageFormat.format("Retry metadata attempt #{0} failed: {1}", context.getRetryCount(), throwable.getMessage()));
            }
        };
    }

    /**
     * Step 3: Acquire FDR payment metadata
     */
    @Bean
    public Step fdrMetadataAcquisitionStep(
        FdrMetadataReader fdrMetadataReader,
        RetryPolicy fdrMetadataRetryPolicy,
        BackOffPolicy fdrMetadataBackOffPolicy,
        RetryListener fdrMetadataRetryListener,
        FdrMetadataProcessor fdrMetadataProcessor,
        FdrMetadataWriter fdrMetadataWriter
    ) {
        return new StepBuilder("fdrMetadataAcquisitionStep", jobRepository)
            .<FrTemp, FdrMetadataProcessor.FdrCompleteData>chunk(batchProperties.getMetadataChunkSize(), transactionManager)
            .reader(fdrMetadataReader)
            .processor(fdrMetadataProcessor)
            .writer(fdrMetadataWriter)
            .faultTolerant()
            .retryPolicy(fdrMetadataRetryPolicy)
            .backOffPolicy(fdrMetadataBackOffPolicy)
            .listener(fdrMetadataRetryListener)
            .retry(RestClientException.class)
            .retryLimit(pagoPAProperties.getMaxRetries())
            .build();
    }

    @Bean
    public RetryPolicy fdrPaymentsRetryPolicy() {
    	return retryPolicy();
    }

    @Bean
    public BackOffPolicy fdrPaymentsBackOffPolicy() {
        return backOffPolicy();
    }

    @Bean
    public RetryListener fdrPaymentsRetryListener() {
    	return new RetryListener() {
        	@Override
        	public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
        		log.info("Retry payments attempt started");
        		return true;
            }
            
            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info("Retry payments attempt closed");
            }
            
            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                log.info(MessageFormat.format("Retry payments attempt #{0} failed: {1}", context.getRetryCount(), throwable.getMessage()));
            }
        };
    }

    /**
     * Step 4: Acquire FDR payment details
     */
    @Bean
    public Step fdrPaymentsAcquisitionStep(
        FdrPaymentsReader fdrPaymentsReader,
        RetryPolicy fdrPaymentsRetryPolicy,
        BackOffPolicy fdrPaymentsBackOffPolicy,
        RetryListener fdrPaymentsRetryListener,
        FdrPaymentsProcessor fdrPaymentsProcessor,
        FdrPaymentsWriter fdrPaymentsWriter
    ) {
        return new StepBuilder("fdrPaymentsAcquisitionStep", jobRepository)
            .<FrTemp, FdrPaymentsProcessor.FdrCompleteData>chunk(batchProperties.getPaymentsChunkSize(), transactionManager)
            .reader(fdrPaymentsReader)
            .processor(fdrPaymentsProcessor)
            .writer(fdrPaymentsWriter)
            .faultTolerant()
            .retryPolicy(fdrPaymentsRetryPolicy)
            .backOffPolicy(fdrPaymentsBackOffPolicy)
            .listener(fdrPaymentsRetryListener)
            .retry(RestClientException.class)
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

}
