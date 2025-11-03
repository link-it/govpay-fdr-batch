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
public class FdrPaymentsWriter implements ItemWriter<FdrPaymentsProcessor.FdrCompleteData> {

    private final FrRepository frRepository;
    private final RendicontazioneRepository rendicontazioneRepository;
    private final DominioRepository dominioRepository;
    private final PagamentoRepository pagamentoRepository;
    private final FrTempRepository frTempRepository;

    public FdrPaymentsWriter(
        FrRepository frRepository,
        RendicontazioneRepository rendicontazioneRepository,
        DominioRepository dominioRepository,
        PagamentoRepository pagamentoRepository,
        FrTempRepository frTempRepository
    ) {
        this.frRepository = frRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
        this.dominioRepository = dominioRepository;
        this.pagamentoRepository = pagamentoRepository;
        this.frTempRepository = frTempRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrPaymentsProcessor.FdrCompleteData> chunk) {
        for (FdrPaymentsProcessor.FdrCompleteData data : chunk) {
            log.info("Writing FDR: domain={}, flow={}, revision={} with {} payments",
                data.getCodDominio(), data.getCodFlusso(), data.getRevision(), data.getPayments().size());

            try {
                // Check if FR already exists
                Optional<Fr> existingFr = frRepository.findByCodFlussoAndCodPspAndRevision(
                    data.getCodFlusso(),
                    data.getCodPsp(),
                    data.getRevision()
                );

                if (existingFr.isPresent()) {
                    log.warn("FDR {} already exists, skipping", data.getCodFlusso());
                    markFrTempAsProcessed(data.getFrTempId());
                    continue;
                }

                // Find domain
                Optional<Dominio> dominioOpt = dominioRepository.findByCodDominio(data.getCodDominio());
                if (dominioOpt.isEmpty()) {
                    log.error("Domain {} not found, skipping FDR {}", data.getCodDominio(), data.getCodFlusso());
                    continue;
                }

                // Create FR entity
                Fr fr = Fr.builder()
                    .dominio(dominioOpt.get())
                    .codFlusso(data.getCodFlusso())
                    .codPsp(data.getCodPsp())
                    .revision(data.getRevision())
                    .stato(data.getStato())
                    .dataFlusso(data.getDataFlusso())
                    .dataRegolamento(data.getDataRegolamento())
                    .identificativoRegolamento(data.getIdentificativoRegolamento())
                    .bicRiversamento(data.getBicRiversamento())
                    .numeroPagamenti(data.getNumeroPagamenti())
                    .importoTotalePagamenti(data.getImportoTotalePagamenti())
                    .codPspMittente(data.getCodPspMittente())
                    .ragioneSocialePsp(data.getRagioneSocialePsp())
                    .codIntermediarioPsp(data.getCodIntermediarioPsp())
                    .codCanale(data.getCodCanale())
                    .dataPubblicazione(data.getDataPubblicazione())
                    .build();

                // Save FR
                fr = frRepository.save(fr);

                // Create and save rendicontazioni
                int savedPayments = 0;
                for (FdrPaymentsProcessor.PaymentData paymentData : data.getPayments()) {
                    // Try to find existing payment for FK reference
                    Optional<Pagamento> pagamentoOpt = pagamentoRepository.findByCodDominioAndIuvAndIndiceDati(
                        data.getCodDominio(),
                        paymentData.getIuv(),
                        paymentData.getIndice()
                    );

                    Rendicontazione rendicontazione = Rendicontazione.builder()
                        .fr(fr)
                        .pagamento(pagamentoOpt.orElse(null))
                        .iuv(paymentData.getIuv())
                        .iur(paymentData.getIur())
                        .indiceDati(paymentData.getIndice())
                        .importo(paymentData.getImporto())
                        .esito(paymentData.getEsito())
                        .dataPagamento(paymentData.getDataPagamento())
                        .stato("ACQUISITO")
                        .build();

                    rendicontazioneRepository.save(rendicontazione);
                    savedPayments++;
                }

                log.info("Saved FDR {} with {} payments", data.getCodFlusso(), savedPayments);

                // Mark FR_TEMP record as processed
                markFrTempAsProcessed(data.getFrTempId());

            } catch (Exception e) {
                log.error("Error writing FDR {}: {}", data.getCodFlusso(), e.getMessage(), e);
                throw e;
            }
        }
    }

    private void markFrTempAsProcessed(Long frTempId) {
        frTempRepository.findById(frTempId).ifPresent(frTemp -> {
            frTemp.setProcessato(true);
            frTempRepository.save(frTemp);
            log.debug("Marked FR_TEMP id={} as processed", frTempId);
        });
    }
}
