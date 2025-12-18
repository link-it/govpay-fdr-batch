package it.govpay.fdr.batch.step4;

import java.text.MessageFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import it.govpay.fdr.batch.Costanti;
import it.govpay.fdr.batch.entity.Dominio;
import it.govpay.fdr.batch.entity.Fr;
import it.govpay.fdr.batch.entity.Pagamento;
import it.govpay.fdr.batch.entity.Rendicontazione;
import it.govpay.fdr.batch.entity.SingoloVersamento;
import it.govpay.fdr.batch.entity.StatoFr;
import it.govpay.fdr.batch.entity.StatoRendicontazione;
import it.govpay.fdr.batch.entity.Versamento;
import it.govpay.fdr.batch.repository.DominioRepository;
import it.govpay.fdr.batch.repository.FrRepository;
import it.govpay.fdr.batch.repository.FrTempRepository;
import it.govpay.fdr.batch.repository.PagamentoRepository;
import it.govpay.fdr.batch.repository.SingoloVersamentoRepository;
import it.govpay.fdr.batch.repository.VersamentoRepository;
import it.govpay.fdr.batch.utils.IuvUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Writer to save FDR complete data to FR and RENDICONTAZIONI tables
 */
@Component
@Slf4j
public class FdrPaymentsWriter implements ItemWriter<FdrPaymentsProcessor.FdrCompleteData> {

    private final FrRepository frRepository;
    private final DominioRepository dominioRepository;
    private final PagamentoRepository pagamentoRepository;
    private final VersamentoRepository versamentoRepository;
    private final SingoloVersamentoRepository singoloVersamentoRepository;
    private final FrTempRepository frTempRepository;

    public FdrPaymentsWriter(
        FrRepository frRepository,
        DominioRepository dominioRepository,
        PagamentoRepository pagamentoRepository,
        VersamentoRepository versamentoRepository,
        SingoloVersamentoRepository singoloVersamentoRepository,
        FrTempRepository frTempRepository
    ) {
        this.frRepository = frRepository;
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
                    log.warn("FDR {} esiste già, salto", data.getCodFlusso());
                    markFrTempAsProcessed(data.getFrTempId());
                } else {
	                // Find domain
	                Optional<Dominio> dominioOpt = dominioRepository.findByCodDominio(data.getCodDominio());
	                if (!dominioOpt.isEmpty()) {
	                    writeProcessedData(data, dominioOpt);

	                    // Mark FR_TEMP record as processed
	                    markFrTempAsProcessed(data.getFrTempId());
	                } else {
	                    log.error("Dominio {} non trovato, salto FDR {}", data.getCodDominio(), data.getCodFlusso());
	                }
                }

            } catch (Exception e) {
                log.error("Errore nella scrittura dell'FDR {}: {}", data.getCodFlusso(), e.getMessage(), e);
                throw e;
            }
        }
    }

	private void writeProcessedData(FdrPaymentsProcessor.FdrCompleteData data, Optional<Dominio> dominioOpt) {
		log.info("Inizio scrittura FdrCompleteData sul DB - Flusso: {}, IUR: {}, Dominio: {}, PSP: {}, Revisione: {}, NumPagamenti: {}, ImportoTotale: {}, DataRegolamento: {}, DataPubblicazione: {}, Payments da salvare: {}",
		    data.getCodFlusso(),
		    data.getIur(),
		    data.getCodDominio(),
		    data.getCodPsp(),
		    data.getRevisione(),
		    data.getNumeroPagamenti(),
		    data.getImportoTotalePagamenti(),
		    data.getDataRegolamento(),
		    data.getDataOraPubblicazione(),
		    data.getPayments() != null ? data.getPayments().size() : 0);

		Dominio dominio = dominioOpt.get();

		Fr fr = buildFR(data, dominio);

		List<String> anomalieFr = new ArrayList<>();
		double  totaleImportiRendicontati = 0.0;

		// Create and save rendicontazioni
		for (FdrPaymentsProcessor.PaymentData paymentData : data.getPayments()) {
		    // Try to find existing payment for FK reference
		    List<Pagamento> pagamenti = findAllPagamenti(data.getCodDominio(), paymentData.getIuv(), paymentData.getIur(), paymentData.getIndiceDati());
		    Pagamento pagamento = (pagamenti.size() == 1 ? pagamenti.get(0) : null);

		    Rendicontazione rendicontazione = buildRendicontazione(fr, paymentData, pagamento);

		    totaleImportiRendicontati += paymentData.getImportoPagato();

		    List<String> anomalieRnd = new ArrayList<>();
		    if (pagamento != null) {
		        // Verifico l'importo
		        verificaImporto(fr, pagamento, rendicontazione, anomalieRnd);
		    } else {
		        gestionePagamentoNoSingleMatch(dominio, fr, pagamenti, rendicontazione, anomalieFr, anomalieRnd);   
		    }

		    // controllo che non sia gia' stata acquisita un rendicontazione per la tupla (codDominio,iuv,iur,indiceDati), in questo caso emetto una anomalia
		    controlloRendicontazioneDuplicata(fr, rendicontazione, anomalieRnd);
		    // gestione anomalie
		    registerAnomalieRendicontazione(rendicontazione, anomalieRnd);

		    fr.addRendicontazione(rendicontazione);
		}

		// Singole rendicontazioni elaborate.
		// Controlli di quadratura generali
		controlliQuadraturaGenerali(fr, data, totaleImportiRendicontati, anomalieFr);

		// Decido lo stato del FR
		if (anomalieFr.isEmpty()) {
		    fr.setStato(Costanti.FLUSSO_STATO_ACCETTATA);
		} else {
		    fr.setDescrizioneStato(String.join("|", anomalieFr));
		    fr.setStato(Costanti.FLUSSO_STATO_ANOMALA);
		}

		// Save FR
		fr = frRepository.save(fr);

		log.info("FDR salvato sul DB - Flusso: {}, IUR: {}, ID: {}, NumPagamenti: {}, ImportoTotale: {}, Stato: {}, Rendicontazioni salvate: {}, Anomalie: {}",
		    data.getCodFlusso(),
		    fr.getIur(),
		    fr.getId(),
		    fr.getNumeroPagamenti(),
		    fr.getImportoTotalePagamenti(),
		    fr.getStato(),
		    fr.getRendicontazioni() != null ? fr.getRendicontazioni().size() : 0,
		    fr.getDescrizioneStato() != null ? "SI" : "NO");
		if (fr.getDescrizioneStato() != null || fr.getRendicontazioni().stream().anyMatch(rnd -> rnd.getAnomalie() != null))
		    log.info("Flusso di rendicontazione acquisito con anomalie.");
		else
		    log.info("Flusso di rendicontazione acquisito senza anomalie.");
	}

	private Rendicontazione buildRendicontazione(Fr fr, FdrPaymentsProcessor.PaymentData paymentData, Pagamento pagamento) {
		return Rendicontazione.builder()
		    .fr(fr)
		    .pagamento(pagamento)
		    .singoloVersamento(pagamento != null ? pagamento.getSingoloVersamento() : null)
		    .iuv(paymentData.getIuv())
		    .iur(paymentData.getIur())
		    .indiceDati(paymentData.getIndiceDati() != null ? paymentData.getIndiceDati().intValue() : null)
		    .importoPagato(paymentData.getImportoPagato())
		    .esito(paymentData.getEsito())
		    .data(paymentData.getData())
		    .stato(StatoRendicontazione.OK)
		    .build();
	}

	private Fr buildFR(FdrPaymentsProcessor.FdrCompleteData data, Dominio dominio) {
		// Create FR entity
		return Fr.builder()
		    .codPsp(data.getCodPsp())
		    .dominio(dominio)
		    .codDominio(dominio.getCodDominio())
		    .codFlusso(data.getCodFlusso())
		    .iur(data.getIur())
		    .dataOraFlusso(data.getDataOraFlusso())
		    .dataRegolamento(data.getDataRegolamento())
		    .dataAcquisizione(LocalDateTime.now())
		    .numeroPagamenti(data.getNumeroPagamenti())
		    .importoTotalePagamenti(data.getImportoTotalePagamenti())
		    .codBicRiversamento(data.getCodBicRiversamento())
		    .ragioneSocialePsp(data.getRagioneSocialePsp())
		    .ragioneSocialeDominio(data.getRagioneSocialeDominio())
		    .dataOraPubblicazione(data.getDataOraPubblicazione())
		    .dataOraAggiornamento(data.getDataOraAggiornamento())
		    .revisione(data.getRevisione())
		    .stato(StatoFr.ACCETTATA)
		    .build();
	}

	private void registerAnomalieRendicontazione(Rendicontazione rendicontazione, List<String> anomalieRnd) {
		if (!anomalieRnd.isEmpty()) {
		    rendicontazione.setAnomalie(String.join("|",anomalieRnd));
		    if (!rendicontazione.getStato().equals(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO))
		        rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_ANOMALA);
		} else {
		    if (!rendicontazione.getStato().equals(Costanti.RENDICONTAZIONE_STATO_ALTRO_INTERMEDIARIO))
		        rendicontazione.setStato(Costanti.RENDICONTAZIONE_STATO_OK);
		}
	}

	private void controlliQuadraturaGenerali(Fr fr, FdrPaymentsProcessor.FdrCompleteData data, double totaleImportiRendicontati, List<String> anomalieFr) {
		// Check amount consistency only if importoTotalePagamenti is not null
		if (fr.getImportoTotalePagamenti() != null && totaleImportiRendicontati != fr.getImportoTotalePagamenti().doubleValue()) {
		    log.info("La somma degli importi rendicontati [{}] non corrisponde al totale indicato nella testata del flusso [{}]", totaleImportiRendicontati, fr.getImportoTotalePagamenti());
		    anomalieFr.add(MessageFormat.format("{0}#La somma degli importi rendicontati [{1}] non corrisponde al totale indicato nella testata del flusso [{2}]", "007106", totaleImportiRendicontati, fr.getImportoTotalePagamenti()));
		} else if (fr.getImportoTotalePagamenti() == null) {
		    log.warn("Importo totale pagamenti è null per FDR {}, salto controllo importo", fr.getCodFlusso());
		}

		// Check payment count consistency only if numeroPagamenti is not null
		if (fr.getNumeroPagamenti() != null && data.getPayments().size() != fr.getNumeroPagamenti().longValue()) {
		    log.info("Il numero di pagamenti rendicontati [{}] non corrisponde al totale indicato nella testata del flusso [{}]", data.getPayments().size(), fr.getNumeroPagamenti());
		    anomalieFr.add(MessageFormat.format("{0}#Il numero di pagamenti rendicontati [{1}] non corrisponde al totale indicato nella testata del flusso [{2}]", "007107", data.getPayments().size(), fr.getNumeroPagamenti()));
		} else if (fr.getNumeroPagamenti() == null) {
		    log.warn("Numero pagamenti è null per FDR {}, salto controllo numero pagamenti", fr.getCodFlusso());
		}
	}

	private void controlloRendicontazioneDuplicata(Fr fr, Rendicontazione rendicontazione, List<String> anomalieRnd) {
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
	}

	private void gestionePagamentoNoSingleMatch(Dominio dominio, Fr fr, List<Pagamento> pagamenti, Rendicontazione rendicontazione, List<String> anomalieFr, List<String> anomalieRnd) {
		if (pagamenti.isEmpty()) {
		    // Pagamento non trovato. Devo capire se ce' un errore.
		    log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] non trovato: ricerco la causa...", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());

		    // controllo se lo IUV e' interno, se non lo e' salto tutta la parte di acquisizione
		    // Se dominio e' null viene considerato non intermediato
		    if(IuvUtils.isIuvInterno(dominio, rendicontazione.getIuv())) {
		        // Recupero il versamento, internamente o dall'applicazione esterna
		        recuperoVersamentoInterno(fr, rendicontazione, anomalieRnd);
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

	private void recuperoVersamentoInterno(Fr fr, Rendicontazione rendicontazione, List<String> anomalieRnd) {
		Optional<Versamento> versamentoOpt = versamentoRepository.findByDominioCodDominioAndIuvPagamento(fr.getCodDominio(), rendicontazione.getIuv());
		Versamento versamento = null;
		if (versamentoOpt.isPresent()) {
		    versamento = associazioneVersamentoInternoLocale(fr, rendicontazione, versamentoOpt);
		    // c'e' almeno una pendenza pagata nel flusso che non ha la RT associata
		} else {
		    // Non e' su sistema. La posizione sara' sanata dal job preposto
		    String erroreVerifica = null;
		    log.info("Non e' stata trovata nessuna pendenza corrispondente alla rendicontazione [Dominio:{} Iuv:{} Iur:{} Indice:{}]: {}.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), erroreVerifica);
		}

		// Controllo se e' un pagamento senza RPT o pagamento standin senza rpt
		if (rendicontazione.getEsito() != null &&
		    (rendicontazione.getEsito().intValue() == Costanti.PAYMENT_STAND_IN_NO_RPT ||
		     rendicontazione.getEsito().intValue() == Costanti.PAYMENT_NO_RPT)) {
		    rendicontazioneSenzaRPT(fr, rendicontazione, versamento, anomalieRnd);
		} else {
		    log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: il pagamento non risulta presente in base dati.", fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
		    anomalieRnd.add(MessageFormat.format("{0}#Il pagamento riferito dalla rendicontazione non risulta presente in base dati.", "007101"));
		}
	}

    private void rendicontazioneSenzaRPT(Fr fr, Rendicontazione rendicontazione, Versamento versamento, List<String> anomalieRnd) {
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
    }

    private Versamento associazioneVersamentoInternoLocale(Fr fr, Rendicontazione rendicontazione, Optional<Versamento> versamentoOpt) {
        Versamento versamento;
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
        return versamento;
    }

    private void verificaImporto(Fr fr, Pagamento pagamento, Rendicontazione rendicontazione, List<String> anomalieRnd) {
        if (rendicontazione.getEsito() != null && rendicontazione.getEsito().intValue() == Costanti.PAYMENT_REVOKED) {
            Double importoRevocato = pagamento.getImportoRevocato();
            if (importoRevocato == null) {
                log.warn("Revoca [Dominio:{} Iuv:{} Iur:{} Indice:{}]: importoRevocato non disponibile nel pagamento",
                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                return;
            }
            if (importoRevocato.compareTo(Math.abs(rendicontazione.getImportoPagato().doubleValue())) != 0) {
                log.info("Revoca [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: l''importo rendicontato [{}] non corrisponde a quanto stornato [{}]",
                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), rendicontazione.getImportoPagato().doubleValue(), importoRevocato);
                anomalieRnd.add(MessageFormat.format("{0}#L''importo rendicontato [{1}] non corrisponde a quanto stornato [{2}]",
                                                  "007112", rendicontazione.getImportoPagato(), importoRevocato));
            }
        } else {
            Double importoPagato = pagamento.getImportoPagato();
            if (importoPagato == null) {
                log.warn("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}]: importoPagato non disponibile nel pagamento",
                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati());
                return;
            }
            if (importoPagato.compareTo(rendicontazione.getImportoPagato()) != 0) {
                log.info("Pagamento [Dominio:{} Iuv:{} Iur:{} Indice:{}] rendicontato con errore: l''importo rendicontato [{}] non corrisponde a quanto pagato [{}]",
                         fr.getCodDominio(), rendicontazione.getIuv(), rendicontazione.getIur(), rendicontazione.getIndiceDati(), rendicontazione.getImportoPagato().doubleValue(), importoPagato);
                anomalieRnd.add(MessageFormat.format("{0}#L''importo rendicontato [{1}] non corrisponde a quanto pagato [{2}]",
                                                  "007104", rendicontazione.getImportoPagato(), importoPagato));
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
