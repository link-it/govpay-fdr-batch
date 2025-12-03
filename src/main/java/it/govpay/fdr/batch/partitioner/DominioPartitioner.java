package it.govpay.fdr.batch.partitioner;

import it.govpay.fdr.batch.repository.FrTempRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.partition.support.Partitioner;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Partitioner che divide il lavoro per cod_dominio.
 * Ogni partizione processa tutti i flussi di un singolo ente creditore.
 */
@Component
@Slf4j
public class DominioPartitioner implements Partitioner {

    private final FrTempRepository frTempRepository;

    public DominioPartitioner(FrTempRepository frTempRepository) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        // Recupera tutti i cod_dominio distinti presenti in FR_TEMP
        List<String> domini = frTempRepository.findDistinctCodDominio();

        log.info("Creazione partizioni: trovati {} domini in FR_TEMP", domini.size());

        Map<String, ExecutionContext> partitions = new HashMap<>();

        for (int i = 0; i < domini.size(); i++) {
            String codDominio = domini.get(i);

            ExecutionContext context = new ExecutionContext();
            context.putString("codDominio", codDominio);
            context.putInt("partitionNumber", i + 1);
            context.putInt("totalPartitions", domini.size());

            // Nome partizione: partition-{dominio}
            String partitionName = "partition-" + codDominio;
            partitions.put(partitionName, context);

            log.debug("Creata partizione #{} per dominio: {}", i + 1, codDominio);
        }

        log.info("Partizioni create: {} (gridSize richiesto: {})", partitions.size(), gridSize);
        return partitions;
    }
}
