package it.govpay.fdr.batch.step4;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.*;
import it.govpay.fdr.batch.repository.*;
import it.govpay.fdr.batch.utils.IuvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final VersamentoRepository versamentoRepository;
    private final SingoloVersamentoRepository singoloVersamentoRepository;
    private final FrTempRepository frTempRepository;

    public FdrPaymentsWriter(
        FrRepository frRepository,
        RendicontazioneRepository rendicontazioneRepository,
        DominioRepository dominioRepository,
        PagamentoRepository pagamentoRepository,
        VersamentoRepository versamentoRepository,
        SingoloVersamentoRepository singoloVersamentoRepository,
        FrTempRepository frTempRepository
    ) {
        this.frRepository = frRepository;
        this.rendicontazioneRepository = rendicontazioneRepository;
        this.dominioRepository = dominioRepository;
        this.pagamentoRepository = pagamentoRepository;
        this.versamentoRepository = versamentoRepository;
        this.singoloVersamentoRepository = singoloVersamentoRepository;
        this.frTempRepository = frTempRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends FdrPaymentsProcessor.FdrCompleteData> chunk) {
        for (FdrPaymentsProcessor.FdrCompleteData data : chunk) {
            log.info("Writing FDR: domain={}, flow={}, pspId={}, revision={} with {} payments",
                data.getCodDominio(), data.getCodFlusso(), data.getCodPsp(), data.getRevisione(), data.getPayments().size());

            try {
                // Check if FR already exists
                Optional<Fr> existingFr = frRepository.findByCodFlussoAndCodPspAndRevisione(
                    data.getCodFlusso(),
                    data.getCodPsp(),
                    data.getRevisione()
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
                Dominio dominio = dominioOpt.get();

                // Create FR entity
                Fr fr = Fr.builder()
                    .codPsp(data.getCodPsp())
                    .dominio(dominio)
                    .codDominio(dominio.getCodDominio())
                    .codFlusso(data.getCodFlusso())
                    .stato(data.getStato())
                    .iur(data.getIur())
                    .dataOraFlusso(data.getDataOraFlusso())
                    .dataRegolamento(data.getDataRegolamento())
                    .dataAcquisizione(Instant.now())
                    .numeroPagamenti(data.getNumeroPagamenti())
                    .importoTotalePagamenti(data.getImportoTotalePagamenti())
                    .codBicRiversamento(data.getCodBicRiversamento())
                    .ragioneSocialePsp(data.getRagioneSocialePsp())
                    .ragioneSocialeDominio(data.getRagioneSocialeDominio())
                    .dataOraPubblicazione(data.getDataOraPubblicazione())
                    .dataOraAggiornamento(data.getDataOraAggiornamento())
                    .revisione(data.getRevisione())
                    .stato("ACCETTATA")
                    .build();

                List<String> anomalieFr = new ArrayList<String>();
                double  totaleImportiRendicontati = 0.0;

                // Create and save rendicontazioni
                for (FdrPaymentsProcessor.PaymentData paymentData : data.getPayments()) {
                    // Try to find existing payment for FK reference
                    List<Pagamento> pagamenti = findAllPagamenti(data.getCodDominio(), paymentData.getIuv(), paymentData.getIur(), paymentData.getIndiceDati());
                    Pagamento pagamento = (pagamenti.size() == 1 ? pagamenti.get(0) : null);

                    Rendicontazione rendicontazione = Rendicontazione.builder()
                        .fr(fr)
                        .pagamento(pagamento)
                        .singoloVersamento(pagamento != null ? pagamento.getSingoloVersamento() : null)
                        .iuv(paymentData.getIuv())
                        .iur(paymentData.getIur())
                        .indiceDati(paymentData.getIndiceDati() != null ? paymentData.getIndiceDati().intValue() : null)
                        .importoPagato(paymentData.getImportoPagato())
                        .esito(paymentData.getEsito())
                        .data(paymentData.getData())
                        .stato("ACQUISITO")
                        .build();

                    totaleImportiRendicontati += paymentData.getImportoPagato();

                    List<String> anomalieRnd = new ArrayList<String>();
                    if (pagamento != null) {
                        // Verifico l'importo
                        if (rendicontazione.getEsito() != null && rendicontazione.getEsito().intValue() == Costanti.PAYMENT_REVOKED) {
                            if(pagamento.getImportoRevocato().compareTo(Math.abs(rendicontazione.getImportoPagato().doubleValue())) != 0) {
                                log.info("Revoca [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: l''importo rendicontato [{}] non corrisponde a quanto stornato [{}]",
                                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), rendicontazione.getImportoPagato().doubleValue(), pagamento.getImportoRevocato().doubleValue());
                                anomalieRnd.add(MessageFormat.format("{0}#L''importo rendicontato [{1}] non corrisponde a quanto stornato [{2}]",
                                                                  "007112", rendicontazione.getImportoPagato(), pagamento.getImportoRevocato().doubleValue()));
                            }
                        } else {
                            if(pagamento.getImportoPagato().compareTo(rendicontazione.getImportoPagato()) != 0) {
                                log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: l''importo rendicontato [{}] non corrisponde a quanto pagato [{}]",
                                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), rendicontazione.getImportoPagato().doubleValue(), pagamento.getImportoRevocato().doubleValue());
                                anomalieRnd.add(MessageFormat.format("{0}#L''importo rendicontato [{1}] non corrisponde a quanto pagato [{2}]",
                                                                  "007104", rendicontazione.getImportoPagato(), pagamento.getImportoPagato().doubleValue()));
                            }
                        }
                    } else {
                        if (pagamenti.size() == 0) {
                            // Pagamento non trovato. Devo capire se ce' un errore.
                            log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] non trovato: ricerco la causa...", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());

                            Versamento versamento = null;
                            // controllo se lo IUV e' interno, se non lo e' salto tutta la parte di acquisizione
                            // Se dominio e' null viene considerato non intermediato
                            if(IuvUtils.isIuvInterno(dominio, rendicontazione.getIuv())) {
                                // Recupero il versamento, internamente o dall'applicazione esterna
                                Optional<Versamento> versamentoOpt = versamentoRepository.findByDominioCodDominioAndIuvPagamento(fr.getCodDominio(), rendicontazione.getIuv());
                                if (versamentoOpt.isPresent()) {
                                    versamento = versamentoOpt.get();
                                    log.info("Trovata Pendenza [{}, {}] in stato [{}], verra' associata alla rendicontazione [Dominio:{} Iuv:{} Iur:{} Indice:{}].",
                                             versamento.getApplicazione().getCodApplicazione(), versamento.getCodVersamentoEnte(), versamento.getStatoVersamento(),
                                             fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());

                                    Set<SingoloVersamento> singoliVersamenti = singoloVersamentoRepository.findAllByVersamentoId(versamento.getId());
                                    int idxRiconciliazione = rendicontazione.getIndiceDati() != null ? rendicontazione.getIndiceDati().intValue() : 1; // se la rendicontazione non ha l'indice dati assumo che sia 1.

                                    for (SingoloVersamento singoloVersamento : singoliVersamenti) {
                                        if(singoloVersamento.getIndiceDati().intValue() == idxRiconciliazione) {
                                            rendicontazione.setSingoloVersamento(singoloVersamento);
                                            break;
                                        }
                                    }
                                    // c'e' almeno una pendenza pagata nel flusso che non ha la RT associata
                                } else {
                                    // Non e' su sistema. Individuo l'applicativo gestore
                                    // TODO
                                    String erroreVerifica = null;
                                    log.info("Non e' stata trovata nessuna pendenza corrispondente alla rendicontazione [Dominio:{} Iuv:{} Iur:{} Indice:{}]: {}.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), erroreVerifica);
                                }

                                // Controllo se e' un pagamento senza RPT o pagamento standin senza rpt
                                if (rendicontazione.getEsito() != null &&
                                    (rendicontazione.getEsito().intValue() == Costanti.PAYMENT_STAND_IN_NO_RPT ||
                                     rendicontazione.getEsito().intValue() == Costanti.PAYMENT_NO_RPT)) {
                                    if (versamento == null) {
                                        // non ho trovato il versamento
                                        log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: Pagamento senza RPT di versamento sconosciuto.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                                        anomalieRnd.add(MessageFormat.format("{0}#Il versamento risulta sconosciuto", "007111"));
                                    } else {
                                        Set<SingoloVersamento> singoliVersamenti = singoloVersamentoRepository.findAllByVersamentoId(versamento.getId());
                                        if(singoliVersamenti.size() != 1) {
                                            // Un pagamento senza rpt DEVE riferire un pagamento tipo 3 con un solo singolo versamento
                                            log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: Pagamento senza RPT di versamento malformato, numero voci maggiore di 1.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                                            anomalieRnd.add(MessageFormat.format("{0}#Il versamento presenta piu'' singoli versamenti", "007114"));
                                        }
                                    }
                                } else {
                                    log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: il pagamento non risulta presente in base dati.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                                    anomalieRnd.add(MessageFormat.format("{0}#Il pagamento riferito dalla rendicontazione non risulta presente in base dati.", "007101"));
                                }
                            } else {
                                log.info("IUV {} appartenente ad un Dominio {} non intermediato, salto acquisizione.", rendicontazione.getIuv(), fr.getCodDominio());
                                rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO);
                            }
                        } else {
                            // Individuati piu' pagamenti riferiti dalla rendicontazione
                            log.info("Pagamento rendicontato duplicato: [Dominio:{} Iuv:{} Iur:{} Indice:{}]", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                            anomalieRnd.add(MessageFormat.format("{0}#La rendicontazione riferisce piu di un pagamento gestito.", "007102"));
                            anomalieFr.add(MessageFormat.format("{0}#La rendicontazione riferisce piu di un pagamento gestito.", "007102"));
                        }   
                    }

                    // controllo che non sia gia' stata acquisita un rendicontazione per la tupla (codDominio,iuv,iur,indiceDati), in questo caso emetto una anomalia
                    log.info("Controllo presenza rendicontazione duplicata all'interno del flusso: [Dominio:{} Iuv:{} Iur:{} Indice:{}]...", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                    for (Rendicontazione r2 : fr.getRendicontazioni()) {
                        if (r2.getIuv().equals(rendicontazione.getIuv()) && r2.getIur().equals(rendicontazione.getIur()) &&
                            ( (r2.getIndiceDati() == null && rendicontazione.getIndiceDati() == null) ||
                              (r2.getIndiceDati() != null && rendicontazione.getIndiceDati() != null && r2.getIndiceDati().compareTo(rendicontazione.getIndiceDati()) == 0))
                            ) {
                            log.info("Rendicontazione [Dominio:{} Iuv:{} Iur:{} Indice:{}] duplicata all''interno del flusso, in violazione delle specifiche PagoPA. Necessario intervento manuale per la risoluzione del problema.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                            anomalieRnd.add(MessageFormat.format("{0}#Rendicontazione [Dominio:{1} Iuv:{2} Iur:{3} Indice:{4}] duplicata all''interno del flusso, in violazione delle specifiche PagoPA. Necessario intervento manuale per la risoluzione del problema.", "007115", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati()));
                            rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_ANOMALA);
                            break;
                        }
                    }
                    log.info("Controllo presenza rendicontazione duplicata all'interno del flusso: [Dominio:{} Iuv:{} Iur:{} Indice:{}] completato", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                    // gestione anomalie
                    if (!anomalieRnd.isEmpty()) {
                        rendicontazione.setAnomalie(String.join("|",anomalieRnd));
                        if (!rendicontazione.getStato().equals(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO))
                            rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_ANOMALA);
                    } else {
                        if (!rendicontazione.getStato().equals(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO))
                            rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_OK);
                    }

                    fr.addRendicontazione(rendicontazione);
                }

                // Singole rendicontazioni elaborate.
                // Controlli di quadratura generali
                if (totaleImportiRendicontati != fr.getImportoTotalePagamenti().doubleValue()) {
                    log.info("La somma degli importi rendicontati [{}] non corrisponde al totale indicato nella testata del flusso [{}]", totaleImportiRendicontati, fr.getImportoTotalePagamenti());
                    anomalieFr.add(MessageFormat.format("{0}#La somma degli importi rendicontati [{1}] non corrisponde al totale indicato nella testata del flusso [{2}]", "007106", totaleImportiRendicontati, fr.getImportoTotalePagamenti()));
                }

                if (data.getPayments().size() != fr.getNumeroPagamenti()) {
					log.info("Il numero di pagamenti rendicontati [{}] non corrisponde al totale indicato nella testata del flusso [{}]", data.getPayments().size(), fr.getNumeroPagamenti());
					anomalieFr.add(MessageFormat.format("{0}#Il numero di pagamenti rendicontati [{1}] non corrisponde al totale indicato nella testata del flusso [{2}]", "007107", data.getPayments().size(), fr.getNumeroPagamenti()));
				}

				// Decido lo stato del FR
				if(anomalieFr.isEmpty()) {
					fr.setStato(Costanti.FLUSSO_STATO_ACCETTATA);
				} else {
                    fr.setDescrizioneStato(String.join("|", anomalieFr));
					fr.setStato(Costanti.FLUSSO_STATO_ANOMALA);
				}

                // Save FR
                fr = frRepository.save(fr);

                log.info("Saved FDR {} with {} payments and stato {}", data.getCodFlusso(), fr.getNumeroPagamenti(), fr.getStato());

                // Mark FR_TEMP record as processed
                markFrTempAsProcessed(data.getFrTempId());

            } catch (Exception e) {
                log.error("Error writing FDR {}: {}", data.getCodFlusso(), e.getMessage(), e);
                throw e;
            }
        }
    }

    private List<Pagamento> findAllPagamenti(String codDominio, String iuv, String iur, Long indiceDati) {
        if (iur != null) {
            if (indiceDati != null)
                return pagamentoRepository.findAllByCodDominioAndIuvAndIurAndIndiceDati(codDominio, iuv, iur, indiceDati);
            return pagamentoRepository.findAllByCodDominioAndIuvAndIur(codDominio, iuv, iur);
        } else {
            if (indiceDati != null)
                return pagamentoRepository.findAllByCodDominioAndIuvAndIndiceDati(codDominio, iuv, indiceDati);
            return pagamentoRepository.findAllByCodDominioAndIuv(codDominio, iuv);
        }
    }

    private void markFrTempAsProcessed(Long frTempId) {
        frTempRepository.findById(frTempId).ifPresent(frTemp -> {
            frTempRepository.delete(frTemp);
            log.debug("Removed FR_TEMP id={}", frTempId);
        });
    }
}
