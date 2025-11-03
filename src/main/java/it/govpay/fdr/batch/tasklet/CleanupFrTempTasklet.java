package it.govpay.fdr.batch.tasklet;

import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tasklet to clean up FR_TEMP table before starting the batch process
 */
@Component
@Slf4j
public class CleanupFrTempTasklet implements Tasklet {

    private final FrTempRepository frTempRepository;

    public CleanupFrTempTasklet(FrTempRepository frTempRepository) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    @Transactional
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        log.info("Starting cleanup of FR_TEMP table");

        long count = frTempRepository.count();
        frTempRepository.deleteAllRecords();

        log.info("Deleted {} records from FR_TEMP table", count);

        return RepeatStatus.FINISHED;
    }
}
