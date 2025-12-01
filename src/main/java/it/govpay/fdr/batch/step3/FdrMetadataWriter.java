package it.govpay.fdr.batch.step3;

import it.govpay.fdr.batch.entity.*;
import it.govpay.fdr.batch.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Writer to save FDR complete data to FR and RENDICONTAZIONI tables
 */
@Component
@Slf4j
public class FdrMetadataWriter implements ItemWriter<FdrMetadataProcessor.FdrCompleteData> {

    private final FrTempRepository frTempRepository;

    public FdrMetadataWriter(
        FrTempRepository frTempRepository
    ) {
        this.frTempRepository = frTempRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrMetadataProcessor.FdrCompleteData> chunk) {
        for (FdrMetadataProcessor.FdrCompleteData data : chunk) {
            log.info("Writing metadata FDR: domain={}, flow={}, revision={}",
                data.getCodDominio(), data.getCodFlusso(), data.getRevisione());

            try {
                Optional<FrTemp> frTempOpt = frTempRepository.findById(data.getFrTempId());
                if (frTempOpt.isEmpty()) {
                    log.error("FR Temp {} not found", data.getFrTempId());
                    continue;
                }

                // store metadata in FR Temp
                FrTemp frTemp = frTempOpt.get();
                frTemp.setCodPsp(data.getCodPsp());
                frTemp.setIur(data.getIur());
                frTemp.setDataOraFlusso(data.getDataOraFlusso());
                frTemp.setDataRegolamento(data.getDataRegolamento());
                frTemp.setNumeroPagamenti(data.getNumeroPagamenti());
                frTemp.setImportoTotalePagamenti(data.getImportoTotalePagamenti());
                frTemp.setCodBicRiversamento(data.getCodBicRiversamento());
                frTemp.setRagioneSocialePsp(data.getRagioneSocialePsp());
                frTemp.setRagioneSocialeDominio(data.getRagioneSocialeDominio());
                frTemp.setDataOraPubblicazione(data.getDataOraPubblicazione());
                frTemp.setDataOraAggiornamento(data.getDataOraAggiornamento());
                frTemp.setStato(data.getStato());

                // Save FR Temp
                frTemp = frTempRepository.save(frTemp);

                log.info("Saved FDR Temp {}", data.getCodFlusso());
            } catch (Exception e) {
                log.error("Errore nella scrittura dell'FDR Temp {}: {}", data.getCodFlusso(), e.getMessage(), e);
                throw e;
            }
        }
    }
}
