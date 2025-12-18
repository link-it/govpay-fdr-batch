package it.govpay.fdr.batch.step2;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.dto.DominioProcessingContext;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.repository.DominioRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Reader for enabled domains to fetch FDR headers.
 * Thread-safe: uses ConcurrentLinkedQueue to distribute domains across threads.
 */
@Component
@StepScope
@Slf4j
public class FdrHeadersReader implements ItemReader<DominioProcessingContext>, StepExecutionListener {

    private final DominioRepository dominioRepository;

    // Thread-safe queue shared across all reader instances within the same step execution
    private static final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ConcurrentLinkedQueue<Object[]>> dominioQueueRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    private static final Object lock = new Object();

    public FdrHeadersReader(DominioRepository dominioRepository) {
        this.dominioRepository = dominioRepository;
    }

    @Override
    public DominioProcessingContext read() {
        // Initialize queue once for all threads (thread-safe)
        java.util.concurrent.ConcurrentLinkedQueue<Object[]> queue = dominioQueueRef.get();
        if (queue == null) {
            synchronized (lock) {
                queue = dominioQueueRef.get();
                if (queue == null) {
                    List<Object[]> dominiInfos = dominioRepository.findDominioWithMaxDataOraPubblicazione();
                    log.info("Trovati {} domini abilitati da processare", dominiInfos.size());
                    queue = new java.util.concurrent.ConcurrentLinkedQueue<>(dominiInfos);
                    dominioQueueRef.set(queue);
                }
            }
        }

        // Each thread polls from the shared queue
        Object[] dominioInfos = queue.poll();
        if (dominioInfos != null) {
            Dominio dominio = (Dominio) dominioInfos[0];
            log.debug("Lettura dominio: {} (thread: {})", dominio.getCodDominio(), Thread.currentThread().getName());

            return DominioProcessingContext.builder()
                .dominioId(dominio.getId())
                .codDominio(dominio.getCodDominio())
                .lastPublicationDate((LocalDateTime) dominioInfos[1])
                .build();
        }

        log.debug("Nessun altro dominio da processare (thread: {})", Thread.currentThread().getName());
        return null; // End of data
    }

    /**
     * Reset queue for next step execution (called by Spring Batch between job executions)
     */
    public static void resetQueue() {
        synchronized (lock) {
            dominioQueueRef.set(null);
        }
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        // Reset queue at the beginning of each step execution
        resetQueue();
        log.debug("Coda domini resettata per nuova esecuzione dello step");
    }
}
