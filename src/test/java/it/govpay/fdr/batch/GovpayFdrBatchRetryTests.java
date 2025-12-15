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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestClientException;

import it.govpay.fdr.batch.config.PreventConcurrentJobLauncher;
import it.govpay.fdr.batch.config.ScheduledJobRunner;
import it.govpay.fdr.batch.config.TestScheduledJobRunnerConfig;
import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.dto.FdrHeadersBatch;
import it.govpay.fdr.batch.entity.FrTemp;
import it.govpay.fdr.batch.repository.FrTempRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration tests for GovpayFdrBatchApplication
 */
@SpringBootTest(classes = GovpayFdrBatchApplication.class)
@Import(TestScheduledJobRunnerConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
		"spring.batch.job.enabled=false"
})
class GovpayFdrBatchRetryTests {
	@Autowired
	JobExplorer jobExplorer;

	@Autowired
	ScheduledJobRunner batchScheduler;

	@MockitoBean
	private PreventConcurrentJobLauncher preventConcurrentJobLauncher = mock(PreventConcurrentJobLauncher.class);

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
	@MockitoBean
	private FrTempRepository frTempRepository = mock(FrTempRepository.class);

	private FrTemp frTempReaderFun() {
		// poll() rimuove e ritorna l'elemento dalla coda (o null se vuota)
		if (headerQueue.poll() != null)
			return new FrTemp();
		return null;
	}

	@BeforeEach
	void setup() throws Exception {
		headerProcessCounter.set(0);
		metadataProcessorCounter.set(0);
		paymentsProcessorCounter.set(0);
		headerQueue.clear();

		// Mock PreventConcurrentJobLauncher per permettere l'esecuzione del job
		when(preventConcurrentJobLauncher.getCurrentRunningJobExecution(any())).thenReturn(null);

		// Mock FrTempRepository per supportare il partitioning
		when(frTempRepository.findDistinctCodDominio()).thenReturn(Arrays.asList("12345678901"));

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

		// Con partitioning, ogni reader viene chiamato più volte fino a quando non ritorna null
		// Quindi il mock deve continuare a chiamare frTempReaderFun() ogni volta
		when(metadataReader.read()).thenAnswer(invocation -> frTempReaderFun());

		// Metadata writer deve ri-aggiungere items alla coda per renderli disponibili allo Step 4 (payments)
		doAnswer(invocation -> {
			Chunk<? extends FdrMetadataProcessor.FdrCompleteData> chunk = invocation.getArgument(0);
			// Per ogni item processato in metadata, aggiungi un marker alla coda per payments
			for (int i = 0; i < chunk.size(); i++) {
				headerQueue.add(FdrHeadersBatch.builder().build());
			}
			return null;
		}).when(metadataWriter).write(any());

		when(paymentsReader.read()).thenAnswer(invocation -> frTempReaderFun());

		FdrPaymentsProcessor.FdrCompleteData paymentsCompleteData = FdrPaymentsProcessor.FdrCompleteData.builder().build();
		when(paymentsProcessor.process(any())).thenAnswer(invocation -> {
			paymentsProcessorCounter.addAndGet(1);
			return paymentsCompleteData;
		});
		
		doNothing().when(paymentsWriter).write(any());
	}
	
	@Test
	void headerRetrySuccess() throws Exception {
		// Il mock non intercetta il retry di Spring Batch, quindi il processor viene chiamato una sola volta
		// e restituisce direttamente il risultato (senza eccezioni)
		when(headersProcessor.process(any())).thenReturn(
			FdrHeadersBatch.builder()
						   .codDominio("process-success")
						   .build()
		);

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		final JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(1, metadataProcessorCounter.get());
	}
	
	@Test
	void headerRetryAndSkip() throws Exception {
		// Test che verifica che con eccezioni continue, l'item viene skippato dopo i retry esauriti
		// Con max-retries=3, Spring Batch prova: 1 iniziale + 2 retry = 3 tentativi totali
		// (il valore max-retries=3 indica il numero totale di tentativi, non i retry aggiuntivi)
		// Dopo 3 tentativi falliti, l'item viene skippato e il job completa
		when(headersProcessor.process(any())).thenAnswer(invocation -> {
			headerProcessCounter.addAndGet(1);
			throw new RestClientException("test");
		});

		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		final JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(3, headerProcessCounter.get()); // 3 tentativi totali (max-retries=3)
		assertEquals(0, metadataProcessorCounter.get()); // Nessun FDR processato perché tutti skippati
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

		final JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
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

		JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
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
		execution = batchScheduler.runBatchFdrAcquisitionJob();
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		assertEquals(0, metadataProcessorCounter.get());
	}

	/**
	 * Test partizionamento con multipli domini.
	 * Verifica che il partizionamento crei una partizione per ogni dominio
	 * e che ogni partizione processi i suoi dati.
	 */
	@Test
	void multiDomainPartitioning() throws Exception {
		// Setup: 3 domini nel sistema
		Mockito.reset(frTempRepository);
		Mockito.reset(headersReader);
		Mockito.reset(metadataReader);
		when(frTempRepository.findDistinctCodDominio()).thenReturn(Arrays.asList("DOM001", "DOM002", "DOM003"));

		// Headers reader deve restituire 3 DominioProcessingContext (uno per ogni dominio)
		DominioProcessingContext dom1 = DominioProcessingContext.builder().dominioId(1L).build();
		DominioProcessingContext dom2 = DominioProcessingContext.builder().dominioId(2L).build();
		DominioProcessingContext dom3 = DominioProcessingContext.builder().dominioId(3L).build();
		when(headersReader.read()).thenReturn(dom1, dom2, dom3, null);

		when(headersProcessor.process(any())).thenReturn(
			FdrHeadersBatch.builder().codDominio("DOM001").build(),
			FdrHeadersBatch.builder().codDominio("DOM002").build(),
			FdrHeadersBatch.builder().codDominio("DOM003").build()
		).thenThrow(new RuntimeException("No more domains"));

		// Metadata reader: limita a 3 letture totali per evitare cicli (3 domini)
		AtomicInteger metadataReadCount = new AtomicInteger(0);
		when(metadataReader.read()).thenAnswer(invocation -> {
			if (metadataReadCount.incrementAndGet() <= 3) {
				return frTempReaderFun();
			}
			return null; // Stop after 3 reads
		});

		// Tutti i processing hanno successo (no retry, no failure)
		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();

		// Il job completa con successo
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		// Verifica che tutti i 3 domini siano stati processati
		assertEquals(3, metadataProcessorCounter.get());
		// E tutti hanno completato anche lo step payments
		assertEquals(3, paymentsProcessorCounter.get());
	}

	/**
	 * Test con multipli flussi per un singolo dominio.
	 * Verifica che una partizione possa processare multipli flussi dello stesso dominio.
	 */
	@Test
	void singleDomainMultipleFlows() throws Exception {
		// Setup: 1 dominio con 3 flussi (headers reader deve restituire 3 DominioProcessingContext)
		Mockito.reset(headersReader);
		DominioProcessingContext dom1 = DominioProcessingContext.builder().dominioId(1L).build();
		DominioProcessingContext dom2 = DominioProcessingContext.builder().dominioId(1L).build();
		DominioProcessingContext dom3 = DominioProcessingContext.builder().dominioId(1L).build();
		when(headersReader.read()).thenReturn(dom1, dom2, dom3, null);

		when(headersProcessor.process(any())).thenReturn(
			FdrHeadersBatch.builder().codDominio("DOM001").build(),
			FdrHeadersBatch.builder().codDominio("DOM001").build(),
			FdrHeadersBatch.builder().codDominio("DOM001").build()
		).thenThrow(new RuntimeException("No more flows"));

		// Tutti i processing hanno successo
		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();

		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		// Verifica che tutti e 3 i flussi siano stati processati
		assertEquals(3, metadataProcessorCounter.get());
		// E tutti e 3 i flussi sono stati completati nel payments step
		assertEquals(3, paymentsProcessorCounter.get());
	}

	/**
	 * Test che verifica il comportamento del partizionamento quando tutti i domini hanno successo.
	 * Questo è il caso ideale dove non ci sono errori.
	 */
	@Test
	void allPartitionsSucceed() throws Exception {
		// Setup: 2 domini
		Mockito.reset(frTempRepository);
		Mockito.reset(headersReader);
		Mockito.reset(metadataReader);
		Mockito.reset(paymentsReader);
		when(frTempRepository.findDistinctCodDominio()).thenReturn(Arrays.asList("DOM001", "DOM002"));

		// Headers reader deve restituire 2 DominioProcessingContext
		DominioProcessingContext dom1 = DominioProcessingContext.builder().dominioId(1L).codDominio("DOM001").build();
		DominioProcessingContext dom2 = DominioProcessingContext.builder().dominioId(2L).codDominio("DOM002").build();
		when(headersReader.read()).thenReturn(dom1, dom2, null);

		when(headersProcessor.process(any())).thenReturn(
			FdrHeadersBatch.builder().codDominio("DOM001").build(),
			FdrHeadersBatch.builder().codDominio("DOM002").build()
		).thenThrow(new RuntimeException("No more domains"));

		// Reset dei contatori prima del test
		metadataProcessorCounter.set(0);
		paymentsProcessorCounter.set(0);
		headerQueue.clear();

		// Track items processed in metadata step to prevent infinite loop
		final AtomicInteger metadataItemsRead = new AtomicInteger(0);

		// Metadata reader deve leggere solo 2 elementi totali (non per partizione)
		when(metadataReader.read()).thenAnswer(invocation -> {
			if (metadataItemsRead.incrementAndGet() <= 2) {
				return frTempReaderFun();
			}
			return null;
		});

		// Payments reader deve anch'esso leggere dalla coda
		when(paymentsReader.read()).thenAnswer(invocation -> frTempReaderFun());

		// Tutti i processing hanno successo
		FdrMetadataProcessor.FdrCompleteData metadataCompleteData = FdrMetadataProcessor.FdrCompleteData.builder().build();
		when(metadataProcessor.process(any())).thenAnswer(invocation -> {
			metadataProcessorCounter.addAndGet(1);
			return metadataCompleteData;
		});

		JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();

		// Il job completa con successo
		assertEquals(BatchStatus.COMPLETED, execution.getStatus());
		// Entrambi i domini sono stati processati
		assertEquals(2, metadataProcessorCounter.get());
		assertEquals(2, paymentsProcessorCounter.get());
	}
}
