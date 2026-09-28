package it.govpay.fdr.batch.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import it.govpay.common.batch.TriggerType;
import it.govpay.common.batch.runner.JobExecutionHelper;
import it.govpay.common.logging.TransactionContext;
import it.govpay.common.logging.level.DynamicLogLevelService;

/**
 * Verifica end-to-end della tracciatura nei batch (BP-LOG-3) su un job reale
 * avviato dal {@link JobExecutionHelper} nel contesto applicativo vero.
 * <p>
 * Copre quello che i test della libreria non possono coprire: che le
 * autoconfigurazioni di govpay-common si attivino davvero in questa applicazione
 * e che il contesto arrivi fino al corpo del job.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = { "spring.batch.job.enabled=false" })
@DisplayName("Test Tracciatura Batch")
class TracciaturaBatchIntegrationTest {

    /** Catturano l'MDC visto dal tasklet, cioe' dal corpo del job. */
    private static final AtomicReference<String> TRANSACTION_ID_NEL_JOB = new AtomicReference<>();
    private static final AtomicReference<String> CORRELATION_ID_NEL_JOB = new AtomicReference<>();

    @TestConfiguration
    static class JobDiProvaConfig {

        @Bean("jobTracciaturaDiProva")
        Job jobTracciaturaDiProva(JobRepository jobRepository,
                @Qualifier("stepTracciaturaDiProva") Step step) {
            return new JobBuilder("jobTracciaturaDiProva", jobRepository).start(step).build();
        }

        @Bean("stepTracciaturaDiProva")
        Step stepTracciaturaDiProva(JobRepository jobRepository,
                PlatformTransactionManager transactionManager) {
            return new StepBuilder("stepTracciaturaDiProva", jobRepository)
                    .tasklet((contribution, chunkContext) -> {
                        TRANSACTION_ID_NEL_JOB.set(TransactionContext.getTransactionId());
                        CORRELATION_ID_NEL_JOB.set(TransactionContext.getCorrelationId());
                        return RepeatStatus.FINISHED;
                    }, transactionManager)
                    .build();
        }
    }

    @Autowired
    private JobExecutionHelper jobExecutionHelper;

    @Autowired
    @Qualifier("jobTracciaturaDiProva")
    private Job jobDiProva;

    @Autowired(required = false)
    private DynamicLogLevelService dynamicLogLevelService;

    @BeforeEach
    void preparaContesto() {
        TransactionContext.clear();
        TRANSACTION_ID_NEL_JOB.set(null);
        CORRELATION_ID_NEL_JOB.set(null);
    }

    @AfterEach
    void pulisciContesto() {
        TransactionContext.clear();
    }

    @Test
    @DisplayName("I livelli di log dinamici sono attivi nel batch")
    void livelliDinamiciAttivi() {
        assertThat(dynamicLogLevelService)
                .as("DynamicLogLevelService deve essere attivo: il batch espone un ConfigurazioneService")
                .isNotNull();
        assertThat(dynamicLogLevelService.loggerGestiti()).contains("it.govpay");
    }

    @Test
    @DisplayName("Il corpo del job vede transaction id e correlation id")
    void ilCorpoDelJobVedeGliIdentificativi() throws Exception {
        JobExecution esecuzione = jobExecutionHelper.runJob(jobDiProva, "jobTracciaturaDiProva",
                TriggerType.SCHEDULED);

        assertThat(esecuzione).isNotNull();
        assertThat(UUID.fromString(TRANSACTION_ID_NEL_JOB.get())).isNotNull();
        assertThat(UUID.fromString(CORRELATION_ID_NEL_JOB.get())).isNotNull();
        assertThat(TRANSACTION_ID_NEL_JOB.get()).isNotEqualTo(CORRELATION_ID_NEL_JOB.get());
    }

    @Test
    @DisplayName("Il correlation id di chi lancia il job viene ereditato")
    void ereditaIlCorrelationIdDelChiamante() throws Exception {
        TransactionContext.setCorrelationId("avvio-manuale-operatore");

        jobExecutionHelper.runJob(jobDiProva, "jobTracciaturaDiProva", TriggerType.MANUAL);

        assertThat(CORRELATION_ID_NEL_JOB.get()).isEqualTo("avvio-manuale-operatore");
    }

    @Test
    @DisplayName("Il correlation id e' persistito fra i parametri di job, non identificante")
    void correlationIdNeiParametriDiJob() throws Exception {
        TransactionContext.setCorrelationId("da-richiesta-rest");

        JobExecution esecuzione = jobExecutionHelper.runJob(jobDiProva, "jobTracciaturaDiProva",
                TriggerType.MANUAL);

        assertThat(esecuzione.getJobParameters()
                .getString(JobExecutionHelper.JOB_PARAM_CORRELATION_ID))
                .isEqualTo("da-richiesta-rest");
        assertThat(esecuzione.getJobParameters()
                .getParameter(JobExecutionHelper.JOB_PARAM_CORRELATION_ID).identifying())
                .as("non deve concorrere all'identita' della JobInstance")
                .isFalse();
    }
}
