package it.govpay.fdr.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.scheduler.FdrBatchScheduler;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for GovpayFdrBatchApplication
 */
@SpringBootTest(classes = GovpayFdrBatchApplication.class)
@TestPropertySource(properties = {
		"spring.batch.job.enabled=false"
})
class GovpayFdrBatchRetryTests {
	@Autowired
	JobExplorer jobExplorer;

	@Autowired
	FdrBatchScheduler batchScheduler;

	private AtomicInteger headerProcessCounter = new AtomicInteger(0);
	private AtomicInteger metadataProcessorCounter = new AtomicInteger(0);
	private AtomicInteger paymentsProcessorCounter = new AtomicInteger(0);
	private Queue<FdrHeadersBatch> headerQueue = new ArrayBlockingQueue<FdrHeadersBatch>(16);

	@MockitoBean
	private CleanupFrTempTasklet cleanupFrTemp = mock(CleanupFrTempTasklet.class);
	@MockitoBean
	private FdrHeadersReader headersReader = mock(FdrHeadersReader.class);
	@MockitoBean
	private FdrHeadersProcessor headersProcessor = mock(FdrHeadersProcessor.class);
	@MockitoBean
	private FdrHeadersWriter headersWriter = mock(FdrHeadersWriter.class);
	@MockitoBean
	private FdrMetadataReader metadataReader = mock(FdrMetadataReader.class);
	@MockitoBean
	private FdrMetadataProcessor metadataProcessor = mock(FdrMetadataProcessor.class);
	@MockitoBean
	private FdrMetadataWriter metadataWriter = mock(FdrMetadataWriter.class);
	@MockitoBean
	private FdrPaymentsReader paymentsReader = mock(FdrPaymentsReader.class);
	@MockitoBean
	private FdrPaymentsProcessor paymentsProcessor = mock(FdrPaymentsProcessor.class);
	@MockitoBean
	private FdrPaymentsWriter paymentsWriter = mock(FdrPaymentsWriter.class);

	private FrTemp frTempReaderFun() {
		if (headerQueue.peek() != null)
			return new FrTemp();
		return null;
	}

	@BeforeEach
	void setup() throws Exception {
		headerProcessCounter.set(0);
		metadataProcessorCounter.set(0);
		paymentsProcessorCounter.set(0);
		headerQueue.clear();

		when(cleanupFrTemp.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);

		DominioProcessingContext res = DominioProcessingContext.builder()
															   .dominioId(101L)
															   .build();
		when(headersReader.read()).thenReturn(res)
								  .thenReturn(null);

		doAnswer(invocation -> { Chunk<? extends FdrHeadersBatch> arg0 = invocation.getArgument(0);
								 headerQueue.add(arg0.getItems().get(0));
								 return null;
							   }).when(headersWriter).write(any());

		when(metadataReader.read()).thenAnswer(invocation -> frTempReaderFun()).thenReturn(null);
		
		doNothing().when(metadataWriter).write(any());
		
		when(paymentsReader.read()).thenAnswer(invocation -> frTempReaderFun()).thenReturn(null);

		FdrPaymentsProcessor.FdrCompleteData paymentsCompleteData = FdrPaymentsProcessor.FdrCompleteData.builder().build();
		when(paymentsProcessor.process(any())).thenAnswer(invocation -> {
			paymentsProcessorCounter.addAndGet(1);
			return paymentsCompleteData;
		});
		
		doNothing().when(paymentsWriter).write(any());
	}
	
	@Test
	void headerRetrySuccess() throws Exception {
		when(headersProcessor.process(any())).thenAnswer(invocation -> {
			if (headerProcessCounter.addAndGet(1) < 3)
				throw new RestClientException("test");
			return FdrHeadersBatch.builder()
								  .codDominio("process-" + headerProcessCounter.get())
								  .build();
		});

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		final JobExecution execution = batchScheduler.runFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(3, headerProcessCounter.get());  // 3 perché la prima e seconda esecuzione vanno in eccezione la terza ritorna correttamente
		assertEquals(1, metadataProcessorCounter.get());
	}
	
	@Test
	void headerRetryAndSkip() throws Exception {
		when(headersProcessor.process(any())).thenAnswer(invocation -> {
			if (headerProcessCounter.addAndGet(1) < 5)
				throw new RestClientException("test");
			return FdrHeadersBatch.builder()
								  .codDominio("process-" + headerProcessCounter.get())
								  .build();
		});

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		final JobExecution execution = batchScheduler.runFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(3, headerProcessCounter.get());
		assertEquals(0, metadataProcessorCounter.get());
	}

	@Test
	void metadataRetrySuccess() throws Exception {
		when(headersProcessor.process(any())).thenReturn( FdrHeadersBatch.builder()
								  .codDominio("process-" + headerProcessCounter.get())
								  .build() ).thenThrow( new RuntimeException("Failed retry headers processor") );

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			if (metadataProcessorCounter.addAndGet(1) < 3)
				throw new RestClientException("test");
			return metadataCompleteData;
		});

		final JobExecution execution = batchScheduler.runFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(3, metadataProcessorCounter.get());
		assertEquals(1, paymentsProcessorCounter.get());
	}

	@Test
	void metadataRetryFailed() throws Exception {
		when(headersProcessor.process(any())).thenReturn( FdrHeadersBatch.builder()
								  .codDominio("process-" + headerProcessCounter.get())
								  .build() ).thenThrow( new RuntimeException("Failed retry headers processor") );

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			if (metadataProcessorCounter.addAndGet(1) < 5)
				throw new RestClientException("test");
			return metadataCompleteData;
		});

		JobExecution execution = batchScheduler.runFdrAcquisitionJob();
		assertEquals(BatchStatus.FAILED, execution.getStatus());
		assertEquals(3, metadataProcessorCounter.get());
		assertEquals(0, paymentsProcessorCounter.get());

		setup();
		Mockito.reset(headersReader);
		Mockito.reset(metadataProcessor);

		when(headersReader.read()).thenReturn(null);

		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});
		execution = batchScheduler.runFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(0, metadataProcessorCounter.get());
	}
}
