package it.govpay.fdr.batch.filesystem;

import java.time.OffsetDateTime;

import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import it.govpay.fdr.batch.dto.FdrFileItem;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.gde.service.GdeService;
import lombok.extern.slf4j.Slf4j;

/**
 * Persiste i flussi letti da file system e archivia i relativi file.
 * <p>
 * Ogni file ha un esito e viene sempre spostato fuori dalla directory di acquisizione:
 * gli acquisiti e i duplicati in {@code processed}, gli scarti e i fallimenti di
 * scrittura in {@code error}. Un errore su un file non interrompe gli altri.
 */
@Component
@Slf4j
public class FdrFileSystemWriter implements ItemWriter<FdrFileItem> {

    public static final String STATS_ACQUISITI = "fileSystemAcquisitiCount";
    public static final String STATS_DUPLICATI = "fileSystemDuplicatiCount";
    public static final String STATS_SCARTATI = "fileSystemScartatiCount";

    private final FdrFileSystemPersister persister;
    private final FdrFileArchiver archiver;
    private final GdeService gdeService;

    private StepExecution stepExecution;

    public FdrFileSystemWriter(FdrFileSystemPersister persister,
                               FdrFileArchiver archiver,
                               GdeService gdeService) {
        this.persister = persister;
        this.archiver = archiver;
        this.gdeService = gdeService;
    }

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        this.stepExecution = stepExecution;
        stepExecution.getExecutionContext().putInt(STATS_ACQUISITI, 0);
        stepExecution.getExecutionContext().putInt(STATS_DUPLICATI, 0);
        stepExecution.getExecutionContext().putInt(STATS_SCARTATI, 0);
    }

    @Override
    public void write(Chunk<? extends FdrFileItem> chunk) {
        for (FdrFileItem item : chunk) {
            switch (item.esito()) {
                case ACQUISIRE -> acquisisci(item);
                case DUPLICATO -> archiviaDuplicato(item);
                case SCARTATO -> archiviaScarto(item);
            }
        }
    }

    private void acquisisci(FdrFileItem item) {
        OffsetDateTime inizio = OffsetDateTime.now();
        int numeroPagamenti = item.data().getPayments().size();
        try {
            persister.persisti(item.data());
        } catch (Exception e) {
            String motivo = "Acquisizione del flusso fallita: " + e.getMessage();
            log.error("Errore nell'acquisizione del flusso {} dal file {}: {}",
                item.codFlusso(), item.nomeOriginale(), e.getMessage(), e);
            archiver.archiviaScartato(item.file(), item.nomeOriginale(), motivo);
            incrementa(STATS_SCARTATI);
            gdeService.saveAcquisizioneFileSystemKo(eventoFr(item), inizio, OffsetDateTime.now(),
                item.nomeOriginale(), motivo);
            return;
        }

        log.info("Flusso {} del dominio {} acquisito dal file {} ({} rendicontazioni)",
            item.codFlusso(), item.codDominio(), item.nomeOriginale(), numeroPagamenti);
        archiver.archiviaProcessato(item.file(), item.nomeOriginale());
        incrementa(STATS_ACQUISITI);
        gdeService.saveAcquisizioneFileSystemOk(eventoFr(item), inizio, OffsetDateTime.now(),
            item.nomeOriginale(), String.format("Acquisito flusso con %d rendicontazioni", numeroPagamenti));
    }

    private void archiviaDuplicato(FdrFileItem item) {
        OffsetDateTime istante = OffsetDateTime.now();
        archiver.archiviaProcessato(item.file(), item.nomeOriginale());
        incrementa(STATS_DUPLICATI);
        gdeService.saveAcquisizioneFileSystemOk(eventoFr(item), istante, OffsetDateTime.now(),
            item.nomeOriginale(), item.motivo());
    }

    private void archiviaScarto(FdrFileItem item) {
        OffsetDateTime istante = OffsetDateTime.now();
        archiver.archiviaScartato(item.file(), item.nomeOriginale(), item.motivo());
        incrementa(STATS_SCARTATI);
        gdeService.saveAcquisizioneFileSystemKo(eventoFr(item), istante, OffsetDateTime.now(),
            item.nomeOriginale(), item.motivo());
    }

    /**
     * Entita' minimale usata solo per popolare gli identificativi dell'evento GDE:
     * di un file scartato prima della validazione puo' essere noto poco o nulla.
     */
    private Fr eventoFr(FdrFileItem item) {
        return Fr.builder()
            .codDominio(item.codDominio())
            .codFlusso(item.codFlusso())
            .codPsp(item.data() != null ? item.data().getCodPsp() : null)
            .revisione(item.data() != null ? item.data().getRevisione() : null)
            .build();
    }

    private void incrementa(String chiave) {
        if (stepExecution == null) {
            return;
        }
        int corrente = stepExecution.getExecutionContext().getInt(chiave, 0);
        stepExecution.getExecutionContext().putInt(chiave, corrente + 1);
    }
}
