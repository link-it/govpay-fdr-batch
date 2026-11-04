package it.govpay.fdr.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.govpay.common.batch.runner.JobExecutionHelper;
import it.govpay.common.batch.service.JobConcurrencyService;
import it.govpay.fdr.batch.config.ScheduledJobRunner;
import it.govpay.fdr.batch.config.TestScheduledJobRunnerConfig;
import it.govpay.fdr.batch.repository.FrTempRepository;
import it.govpay.fdr.batch.step2.FdrHeadersReader;
import it.govpay.fdr.batch.step3.FdrMetadataReader;
import it.govpay.fdr.batch.step4.FdrPaymentsReader;
import it.govpay.fdr.batch.tasklet.CleanupFrTempTasklet;

/**
 * Verifica end-to-end dell'esposizione delle metriche Prometheus:
 * <ul>
 *   <li>lo scrape {@code GET /actuator/prometheus} risponde in formato
 *       testuale Prometheus, con il tag comune {@code application};</li>
 *   <li>l'esecuzione del job pubblica le metriche standard di Spring Batch
 *       ({@code spring_batch_job}) e quelle di govpay-common
 *       ({@code govpay_batch_*}), con il tag {@code nodo} uguale al cluster-id.</li>
 * </ul>
 *
 * <p>Il servizio non ha una porta management separata: essendo l'unico
 * server web presente quello dell'actuator, scrape e health rispondono sulla
 * stessa porta (qui random per il test).
 */
@SpringBootTest(classes = GovpayFdrBatchApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestScheduledJobRunnerConfig.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.batch.job.enabled=false",
        // application.properties imposta initialDelayString=1 (1ms): senza questo
        // override lo @Scheduled reale lancia fdrAcquisitionJob quasi subito, in
        // corsa con la chiamata esplicita del test sullo stesso job.
        "scheduler.initialDelayString=3600000",
        "govpay.batch.cluster-id=" + PrometheusScrapeIntegrationTest.NODO
})
class PrometheusScrapeIntegrationTest {

    static final String NODO = "nodo-test";

    @LocalServerPort
    private int serverPort;

    @Autowired
    private ScheduledJobRunner batchScheduler;

    @MockitoBean
    private JobConcurrencyService jobConcurrencyService = mock(JobConcurrencyService.class);
    @MockitoBean
    private CleanupFrTempTasklet cleanupFrTemp = mock(CleanupFrTempTasklet.class);
    @MockitoBean
    private FdrHeadersReader headersReader = mock(FdrHeadersReader.class);
    @MockitoBean
    private FrTempRepository frTempRepository = mock(FrTempRepository.class);
    @MockitoBean
    private FdrMetadataReader metadataReader = mock(FdrMetadataReader.class);
    @MockitoBean
    private FdrPaymentsReader paymentsReader = mock(FdrPaymentsReader.class);

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void scrapeReturnsPrometheusFormatWithApplicationTag() throws Exception {
        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("text/plain");
        assertThat(response.body()).contains("# TYPE jvm_memory_used_bytes gauge");
        assertThat(response.body()).contains("application=\"govpay-fdr-batch\"");
    }

    @Test
    void batchJobExecutionProducesSpringBatchMetrics() throws Exception {
        // Job "a vuoto": nessun dominio da processare, nessun header/metadata/payment
        // in coda. Basta a far girare il job (anche se termina senza item) e a
        // produrre le metriche standard spring_batch_job/step di Micrometer.
        when(jobConcurrencyService.getCurrentRunningJobExecution(any())).thenReturn(null);
        when(cleanupFrTemp.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        when(frTempRepository.findDistinctCodDominio()).thenReturn(List.of());
        when(headersReader.read()).thenReturn(null);
        when(metadataReader.read()).thenReturn(null);
        when(paymentsReader.read()).thenReturn(null);

        JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
        assertThat(execution).isNotNull();

        String scrape = get("/actuator/prometheus").body();
        assertThat(scrape).contains("spring_batch_job_seconds_count");
        assertThat(scrape).contains("spring_batch_job_name=\"fdrAcquisitionJob\"");
    }

    @Test
    void batchJobExecutionProducesGovpayBatchMetrics() throws Exception {
        when(jobConcurrencyService.getCurrentRunningJobExecution(any())).thenReturn(null);
        when(cleanupFrTemp.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
        when(frTempRepository.findDistinctCodDominio()).thenReturn(List.of());
        when(headersReader.read()).thenReturn(null);
        when(metadataReader.read()).thenReturn(null);
        when(paymentsReader.read()).thenReturn(null);

        JobExecution execution = batchScheduler.runBatchFdrAcquisitionJob();
        assertThat(execution).isNotNull();
        // Senza incrementer sul job i parametri dell'helper arrivano all'esecuzione (govpay-common#39)
        assertThat(execution.getJobParameters().getString(JobExecutionHelper.JOB_PARAM_CLUSTER_ID)).isEqualTo(NODO);
        assertThat(execution.getJobParameters().getString(JobExecutionHelper.JOB_PARAM_TRIGGER_TYPE)).isEqualTo("SCHEDULED");
        assertThat(execution.getJobParameters().getString(JobExecutionHelper.JOB_PARAM_CORRELATION_ID)).isNotBlank();

        String scrape = get("/actuator/prometheus").body();
        String nodo = "nodo=\"" + NODO + "\"";
        assertThat(righe(scrape, "govpay_batch_job_seconds_count{"))
                .anyMatch(r -> r.contains("job_name=\"fdrAcquisitionJob\"") && r.contains("trigger=\"SCHEDULED\"")
                        && r.contains("esito=\"COMPLETED\"") && r.contains(nodo));
        assertThat(righe(scrape, "govpay_batch_schedule_ticks_total{"))
                .anyMatch(r -> r.contains("esito_tick=\"CAN_PROCEED\"") && r.contains(nodo));
        assertThat(righe(scrape, "govpay_batch_schedule_interval_seconds{"))
                .anyMatch(r -> r.contains("tipo=\"fixedDelay\"") && r.contains(nodo) && r.endsWith(" 7200.0"));
        assertThat(righe(scrape, "govpay_batch_stale_threshold_seconds{")).anyMatch(r -> r.contains(nodo));
        assertThat(righe(scrape, "govpay_batch_job_last_success_timestamp_seconds{")).anyMatch(r -> r.contains(nodo));
    }

    private static List<String> righe(String scrape, String prefisso) {
        return scrape.lines().filter(r -> r.startsWith(prefisso)).toList();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + serverPort + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
