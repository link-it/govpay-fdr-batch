package it.govpay.fdr.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import it.govpay.common.logging.MdcTaskDecorator;

/**
 * Configurazione del task executor per l'elaborazione parallela degli step batch.
 * <p>
 * Definito in una configurazione dedicata (senza dipendenze JPA) per evitare il
 * ciclo di bean introdotto da Spring Boot 4: {@code entityManagerFactoryBuilder}
 * richiede un {@code ObjectProvider<AsyncTaskExecutor>} (bootstrapExecutor) e, se
 * il task executor fosse dichiarato in {@link BatchJobConfiguration} — che dipende
 * dal {@code transactionManager} e quindi dall'{@code entityManagerFactory} — si
 * formerebbe una dipendenza circolare.
 */
@Configuration
public class BatchTaskExecutorConfig {

    private final BatchProperties batchProperties;

    public BatchTaskExecutorConfig(BatchProperties batchProperties) {
        this.batchProperties = batchProperties;
    }

    /**
     * Task executor per l'elaborazione parallela degli step partizionati/multi-thread.
     */
    @Bean
    public SimpleAsyncTaskExecutor taskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("fdr-batch-");
        executor.setConcurrencyLimit(batchProperties.getThreadPoolSize());
        // Propaga transaction id e correlation id ai thread delle partizioni
        // (BP-LOG-3): senza decoratore i log paralleli perderebbero gli
        // identificativi dell'esecuzione che li ha generati.
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
